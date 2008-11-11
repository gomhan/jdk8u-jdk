/*
 * Portions Copyright 2003-2005 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

/*
 *******************************************************************************
 * (C) Copyright IBM Corp. 1996-2005 - All Rights Reserved                     *
 *                                                                             *
 * The original version of this source code and documentation is copyrighted   *
 * and owned by IBM, These materials are provided under terms of a License     *
 * Agreement between IBM and Sun. This technology is protected by multiple     *
 * US and International patents. This notice and attribution to IBM may not    *
 * to removed.                                                                 *
 *******************************************************************************
 */

package sun.text.normalizer;

import java.io.InputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * <p>A trie is a kind of compressed, serializable table of values
 * associated with Unicode code points (0..0x10ffff).</p>
 * <p>This class defines the basic structure of a trie and provides methods
 * to <b>retrieve the offsets to the actual data</b>.</p>
 * <p>Data will be the form of an array of basic types, char or int.</p>
 * <p>The actual data format will have to be specified by the user in the
 * inner static interface com.ibm.icu.impl.Trie.DataManipulate.</p>
 * <p>This trie implementation is optimized for getting offset while walking
 * forward through a UTF-16 string.
 * Therefore, the simplest and fastest access macros are the
 * fromLead() and fromOffsetTrail() methods.
 * The fromBMP() method are a little more complicated; they get offsets even
 * for lead surrogate codepoints, while the fromLead() method get special
 * "folded" offsets for lead surrogate code units if there is relevant data
 * associated with them.
 * From such a folded offsets, an offset needs to be extracted to supply
 * to the fromOffsetTrail() methods.
 * To handle such supplementary codepoints, some offset information are kept
 * in the data.</p>
 * <p>Methods in com.ibm.icu.impl.Trie.DataManipulate are called to retrieve
 * that offset from the folded value for the lead surrogate unit.</p>
 * <p>For examples of use, see com.ibm.icu.impl.CharTrie or
 * com.ibm.icu.impl.IntTrie.</p>
 * @author synwee
 * @see com.ibm.icu.impl.CharTrie
 * @see com.ibm.icu.impl.IntTrie
 * @since release 2.1, Jan 01 2002
 */
public abstract class Trie
{
    // public class declaration ----------------------------------------

    /**
    * Character data in com.ibm.impl.Trie have different user-specified format
    * for different purposes.
    * This interface specifies methods to be implemented in order for
    * com.ibm.impl.Trie, to surrogate offset information encapsulated within
    * the data.
    * @draft 2.1
    */
    public static interface DataManipulate
    {
        /**
        * Called by com.ibm.icu.impl.Trie to extract from a lead surrogate's
        * data
        * the index array offset of the indexes for that lead surrogate.
        * @param value data value for a surrogate from the trie, including the
        *        folding offset
        * @return data offset or 0 if there is no data for the lead surrogate
        * @draft 2.1
        */
        public int getFoldingOffset(int value);
    }

    // protected constructor -------------------------------------------

    /**
    * Trie constructor for CharTrie use.
    * @param inputStream ICU data file input stream which contains the
    *                        trie
    * @param dataManipulate object containing the information to parse the
    *                       trie data
    * @throws IOException thrown when input stream does not have the
    *                        right header.
    * @draft 2.1
    */
    protected Trie(InputStream inputStream,
                   DataManipulate  dataManipulate) throws IOException
    {
        DataInputStream input = new DataInputStream(inputStream);
        // Magic number to authenticate the data.
        int signature = input.readInt();
        m_options_    = input.readInt();

        if (!checkHeader(signature)) {
            throw new IllegalArgumentException("ICU data file error: Trie header authentication failed, please check if you have the most updated ICU data file");
        }

        m_dataManipulate_ = dataManipulate;
        m_isLatin1Linear_ = (m_options_ &
                             HEADER_OPTIONS_LATIN1_IS_LINEAR_MASK_) != 0;
        m_dataOffset_     = input.readInt();
        m_dataLength_     = input.readInt();
        unserialize(inputStream);
    }

    /**
    * Trie constructor
    * @param index array to be used for index
    * @param options used by the trie
    * @param dataManipulate object containing the information to parse the
    *                       trie data
    * @draft 2.2
    */
    protected Trie(char index[], int options, DataManipulate dataManipulate)
    {
        m_options_ = options;
        m_dataManipulate_ = dataManipulate;
        m_isLatin1Linear_ = (m_options_ &
                             HEADER_OPTIONS_LATIN1_IS_LINEAR_MASK_) != 0;
        m_index_ = index;
        m_dataOffset_ = m_index_.length;
    }


    // protected data members ------------------------------------------

    /**
    * Lead surrogate code points' index displacement in the index array.
    * 0x10000-0xd800=0x2800
    * 0x2800 >> INDEX_STAGE_1_SHIFT_
    */
    protected static final int LEAD_INDEX_OFFSET_ = 0x2800 >> 5;
    /**
    * Shift size for shifting right the input index. 1..9
    * @draft 2.1
    */
    protected static final int INDEX_STAGE_1_SHIFT_ = 5;
    /**
    * Shift size for shifting left the index array values.
    * Increases possible data size with 16-bit index values at the cost
    * of compactability.
    * This requires blocks of stage 2 data to be aligned by
    * DATA_GRANULARITY.
    * 0..INDEX_STAGE_1_SHIFT
    * @draft 2.1
    */
    protected static final int INDEX_STAGE_2_SHIFT_ = 2;
    /**
    * Mask for getting the lower bits from the input index.
    * DATA_BLOCK_LENGTH_ - 1.
    * @draft 2.1
    */
    protected static final int INDEX_STAGE_3_MASK_ =
                                              (1 << INDEX_STAGE_1_SHIFT_) - 1;
    /**
    * Surrogate mask to use when shifting offset to retrieve supplementary
    * values
    * @draft 2.1
    */
    protected static final int SURROGATE_MASK_ = 0x3FF;
    /**
    * Index or UTF16 characters
    * @draft 2.1
    */
    protected char m_index_[];
    /**
    * Internal TrieValue which handles the parsing of the data value.
    * This class is to be implemented by the user
    * @draft 2.1
    */
    protected DataManipulate m_dataManipulate_;
    /**
    * Start index of the data portion of the trie. CharTrie combines
    * index and data into a char array, so this is used to indicate the
    * initial offset to the data portion.
    * Note this index always points to the initial value.
    * @draft 2.1
    */
    protected int m_dataOffset_;
    /**
    * Length of the data array
    */
    protected int m_dataLength_;

    // protected methods -----------------------------------------------

    /**
    * Gets the offset to the data which the surrogate pair points to.
    * @param lead lead surrogate
    * @param trail trailing surrogate
    * @return offset to data
    * @draft 2.1
    */
    protected abstract int getSurrogateOffset(char lead, char trail);

    /**
    * Gets the value at the argument index
    * @param index value at index will be retrieved
    * @return 32 bit value
    * @draft 2.1
    */
    protected abstract int getValue(int index);

    /**
    * Gets the default initial value
    * @return 32 bit value
    * @draft 2.1
    */
    protected abstract int getInitialValue();

    /**
    * Gets the offset to the data which the index ch after variable offset
    * points to.
    * Note for locating a non-supplementary character data offset, calling
    * <p>
    * getRawOffset(0, ch);
    * </p>
    * will do. Otherwise if it is a supplementary character formed by
    * surrogates lead and trail. Then we would have to call getRawOffset()
    * with getFoldingIndexOffset(). See getSurrogateOffset().
    * @param offset index offset which ch is to start from
    * @param ch index to be used after offset
    * @return offset to the data
    * @draft 2.1
    */
    protected final int getRawOffset(int offset, char ch)
    {
        return (m_index_[offset + (ch >> INDEX_STAGE_1_SHIFT_)]
                << INDEX_STAGE_2_SHIFT_)
                + (ch & INDEX_STAGE_3_MASK_);
    }

    /**
    * Gets the offset to data which the BMP character points to
    * Treats a lead surrogate as a normal code point.
    * @param ch BMP character
    * @return offset to data
    * @draft 2.1
    */
    protected final int getBMPOffset(char ch)
    {
        return (ch >= UTF16.LEAD_SURROGATE_MIN_VALUE
                && ch <= UTF16.LEAD_SURROGATE_MAX_VALUE)
                ? getRawOffset(LEAD_INDEX_OFFSET_, ch)
                : getRawOffset(0, ch);
                // using a getRawOffset(ch) makes no diff
    }

    /**
    * Gets the offset to the data which this lead surrogate character points
    * to.
    * Data at the returned offset may contain folding offset information for
    * the next trailing surrogate character.
    * @param ch lead surrogate character
    * @return offset to data
    * @draft 2.1
    */
    protected final int getLeadOffset(char ch)
    {
       return getRawOffset(0, ch);
    }

    /**
    * Internal trie getter from a code point.
    * Could be faster(?) but longer with
    *   if((c32)<=0xd7ff) { (result)=_TRIE_GET_RAW(trie, data, 0, c32); }
    * Gets the offset to data which the codepoint points to
    * @param ch codepoint
    * @return offset to data
    * @draft 2.1
    */
    protected final int getCodePointOffset(int ch)
    {
        // if ((ch >> 16) == 0) slower
        if (ch >= UTF16.CODEPOINT_MIN_VALUE
            && ch < UTF16.SUPPLEMENTARY_MIN_VALUE) {
            // BMP codepoint
            return getBMPOffset((char)ch);
        }
        // for optimization
        if (ch >= UTF16.CODEPOINT_MIN_VALUE
            && ch <= UCharacter.MAX_VALUE) {
            // look at the construction of supplementary characters
            // trail forms the ends of it.
            return getSurrogateOffset(UTF16.getLeadSurrogate(ch),
                                      (char)(ch & SURROGATE_MASK_));
        }
        // return -1 if there is an error, in this case we return
        return -1;
    }

    /**
    * <p>Parses the inputstream and creates the trie index with it.</p>
    * <p>This is overwritten by the child classes.
    * @param inputStream input stream containing the trie information
    * @exception IOException thrown when data reading fails.
    * @draft 2.1
    */
    protected void unserialize(InputStream inputStream) throws IOException
    {
        //indexLength is a multiple of 1024 >> INDEX_STAGE_2_SHIFT_
        m_index_              = new char[m_dataOffset_];
        DataInputStream input = new DataInputStream(inputStream);
        for (int i = 0; i < m_dataOffset_; i ++) {
             m_index_[i] = input.readChar();
        }
    }

    /**
    * Determines if this is a 32 bit trie
    * @return true if options specifies this is a 32 bit trie
    * @draft 2.1
    */
    protected final boolean isIntTrie()
    {
        return (m_options_ & HEADER_OPTIONS_DATA_IS_32_BIT_) != 0;
    }

    /**
    * Determines if this is a 16 bit trie
    * @return true if this is a 16 bit trie
    * @draft 2.1
    */
    protected final boolean isCharTrie()
    {
        return (m_options_ & HEADER_OPTIONS_DATA_IS_32_BIT_) == 0;
    }

    // private data members --------------------------------------------

    /**
    * Signature index
    */
    private static final int HEADER_SIGNATURE_INDEX_ = 0;
    /**
    * Options index
    */
    private static final int HEADER_OPTIONS_INDEX_ = 1 << 1;
    /**
    * Index length index
    */
    private static final int HEADER_INDEX_LENGTH_INDEX_ = 2 << 1;
    /**
    * Data length index
    */
    private static final int HEADER_DATA_LENGTH_INDEX_ = 3 << 1;
    /**
    * Size of header
    */
    private static final int HEADER_LENGTH_ = 4 << 1;
    /**
    * Latin 1 option mask
    */
    private static final int HEADER_OPTIONS_LATIN1_IS_LINEAR_MASK_ = 0x200;
    /**
    * Constant number to authenticate the byte block
    */
    private static final int HEADER_SIGNATURE_ = 0x54726965;
    /**
    * Header option formatting
    */
    private static final int HEADER_OPTIONS_SHIFT_MASK_ = 0xF;
    private static final int HEADER_OPTIONS_INDEX_SHIFT_ = 4;
    private static final int HEADER_OPTIONS_DATA_IS_32_BIT_ = 0x100;

    /**
    * Flag indicator for Latin quick access data block
    */
    private boolean m_isLatin1Linear_;

    /**
    * <p>Trie options field.</p>
    * <p>options bit field:<br>
    * 9  1 = Latin-1 data is stored linearly at data + DATA_BLOCK_LENGTH<br>
    * 8  0 = 16-bit data, 1=32-bit data<br>
    * 7..4  INDEX_STAGE_1_SHIFT   // 0..INDEX_STAGE_2_SHIFT<br>
    * 3..0  INDEX_STAGE_2_SHIFT   // 1..9<br>
    */
    private int m_options_;

    // private methods ---------------------------------------------------

    /**
    * Authenticates raw data header.
    * Checking the header information, signature and options.
    * @param rawdata array of char data to be checked
    * @return true if the header is authenticated valid
    * @draft 2.1
    */
    private final boolean checkHeader(int signature)
    {
        // check the signature
        // Trie in big-endian US-ASCII (0x54726965).
        // Magic number to authenticate the data.
        if (signature != HEADER_SIGNATURE_) {
            return false;
        }

        if ((m_options_ & HEADER_OPTIONS_SHIFT_MASK_) !=
                                                    INDEX_STAGE_1_SHIFT_ ||
            ((m_options_ >> HEADER_OPTIONS_INDEX_SHIFT_) &
                                                HEADER_OPTIONS_SHIFT_MASK_)
                                                 != INDEX_STAGE_2_SHIFT_) {
            return false;
        }
        return true;
    }
}
