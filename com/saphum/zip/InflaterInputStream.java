// ---------------------------------------------------------------------------
//
//  MIDP Inflater
//  Copyright (c) 2004,2005  Peter Heinrich
//
//  This program is free software; you can redistribute it and/or
//  modify it under the terms of the GNU General Public License
//  as published by the Free Software Foundation; either version 2
//  of the License, or (at your option) any later version.
//
//  Linking this library statically or dynamically with other modules
//  is making a combined work based on this library. Thus, the terms
//  and conditions of the GNU General Public License cover the whole
//  combination.
//
//  As a special exception, the copyright holders of this library give
//  you permission to link this library with independent modules to
//  produce an executable, regardless of the license terms of these
//  independent modules, and to copy and distribute the resulting
//  executable under terms of your choice, provided that you also meet,
//  for each linked independent module, the terms and conditions of the
//  license of that module. An independent module is a module which is
//  not derived from or based on this library. If you modify this
//  library, you may extend this exception to your version of the
//  library, but you are not obligated to do so. If you do not wish to
//  do so, delete this exception statement from your version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program; if not, write to the Free Software
//  Foundation, Inc., 51 Franklin Street, Boston, MA  02110-1301, USA.
//
// ---------------------------------------------------------------------------



package com.saphum.zip;



import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;



/**
 * This class implements a stream filter for uncompressing "deflated" data.
 * The deflate compression format was originally developed for use by the PNG
 * graphics format, and is unencumbered by patents.  It forms the basis of the
 * zlib library.<p/>
 *
 * The functionality here matches the
 * <code>java.util.zip.InflaterInputStream</code> class from the Java 2
 * Standard Edition, but the implementation differs to accomodate the reduced
 * resources available on J2ME.  Unfortunately, this J2ME version necessarily
 * depends on pure Java; no native method support is possible (the J2SE
 * version basically calls through to zlib).<p/>
 *
 * Deflate combines three compression methods: Lempel-Ziv (LZ77), Huffman
 * coding, and run-length encoding (RLE).  Here's a brief overview of each and
 * how they contribute to the complete algorithm.<p/>
 *
 * LZ77 is a dictionary coder that uses a "sliding window": the algorithm
 * works by comparing the current data (being encoded) to a history window of
 * the most recently seen data.  It then outputs to the compressed stream a
 * reference to the position in the history window, and the length of the
 * match.<p/>
 *
 * Huffman coding uses a tree of variable-length codes to encode "symbols,"
 * such as bytes in a file.  It is an entropy coder, in that the length of a
 * code is inversely proportional to the probability of the symbol it
 * represents.  When the symbols to be encoded are ordered, the Huffman tree
 * (usually implemented as a table) may be uniquely defined by the similarly
 * ordered lengths of the corresponding codes.<p/>
 *
 * Run-length encoding is an almost trivial compression method in which
 * <em>runs</em> of data (that is, sequences of repeated data elements) are
 * stored as a single value/count pair.<p/>
 * 
 * The foundation of Deflate is LZ77, which produces a stream of
 * <strong>literals</strong> (bytes) and <strong>symbols</strong> (references
 * to previous data, plus match lengths), both of which are represented by
 * 9-bit codes.  (The first 256 codes correspond to the literals; the value
 * 256 itself means "end of data"; and codes 257-288 each represent a length
 * of a previously seen string of bytes to be replicated.)  When a code less
 * than 256 is encountered, it is taken to be a literal and copied directly to
 * the output stream.  A value of 256 indicates all data has been decompressed
 * and no more codes should be processed.  When a length code is encountered,
 * however, it's used to look up a <em>base</em> length, then, depending on
 * that base length's magnitude, additional bits are read to "pinpoint" the
 * value.<p/>
 *
 * For example, LZ77 associates the code 279 with a base length of 99, then
 * uses the next 4 bits from the compressed stream as an addend modifier.  (If
 * the next 4 bits were 1010, for instance, LZ77 would add 10 to 99 to compute
 * an actual length of 109.)  Immediately following the length code is a
 * distance identifier, used in a similar fashion to look up a base distance
 * (backwards in the window of recently decompressed data) from which to start
 * copying.<p/>
 *
 * So, an LZ77-compressed stream is composed of 9-bit codes (interspersed with
 * "extra" length bits and distance identifiers) that define an "alphabet" of
 * symbols ranging from 0-288.  Deflate associates a variable-length code with
 * each one, defining a Huffman coding tree, in one of two ways.  The first is
 * to dynamically generate the codes, based on a frequency analysis of the
 * data.  Unfortunately, these computed codes must then be included in the
 * compressed stream so that the decompressor be able to reconstitute the
 * Huffman tree.  The second is to use predefined codes, the advantage being
 * that they need not be included in the data, though they probably (almost
 * certainly) won't represent the symbols optimally.<p/>
 *
 * Most deflated streams use dynamic Huffman codes for this reason, despite
 * the extra storage overhead.  In fact, this is where the final compression
 * method is applied.  As mentioned above, a Huffman tree may be uniquely
 * described by an ordered list of the lengths associated with a known
 * alphabet.  This means that once the variable-length codes are computed,
 * Deflate only needs to store their lengths, in order, for the decompressor
 * to be able to rebuild the tree.  LZ77 is designed to ensure all code
 * lengths are 0-15 bits, with 0 indicating the corresponding symbol in the
 * alphabet doesn't occur in the compressed data.</p>
 *
 * The list of code lengths is run-length encoded using a simple scheme:
 * values 0-15 represent actual code lengths; 16 means copy the previous
 * length 3-6 times; 17 means repeat a length of 0 for 3-10 times; and 18
 * means repeat a length of 0 for 11-138 times.  All lengths are specified
 * with extra bits following the codes themselves, similar to lengths and
 * distances in the LZ77 stream.<p/>
 *
 * In a pathological twist, the RLE data is then Huffman encoded as well, with
 * the alphabet defined as 0-15 plus the special codes 16, 17, and 18.  These
 * 19 codes require at most 5 bits each.  The alphabet's order is always
 * fixed, with the special codes coming first, followed by lengths most likely
 * to be encountered (0, 8, 7, 9, 6, 10, etc.).  Since the maximum code length
 * is now 5, which can be represented in 3 bits, a stream compressed with
 * dynamic Huffman codes always starts with a table of 19 3-bit values.</p>
 *
 * A few final points:  Deflate compresses data in blocks, each of which may
 * use dynamic Huffman codes, fixed Huffman codes, or no compression at all,
 * independent of the others.  GZip and ZLib both use the deflate algorithm to
 * compress data, but they package their compressed streams with different
 * wrappers and compute different checksums (GZip uses CRC32; ZLib uses
 * Adler32, an improved version of Fletcher).  In addition, ZLib allows the
 * compressor to alter the size of the sliding window, down to as little as
 * 512 bytes; GZip always assumes the stream was compressed using the maximum
 * allowable window size of 32,768 bytes.
 *
 * @author Peter Heinrich
 * @see <a href="http://tinyurl.com/bqd8q">A Universal Algorithm for
 * Sequential Data Compression (LZ77)</a>
 * @see <a href="http://en.wikipedia.org/wiki/LZ77">LZ77 on Wikipedia</a>
 * @see <a href="http://tinyurl.com/cosc8">A Method for the Construction of
 * Minimum-Redundancy Codes (Huffman coding)</a>
 * @see <a href="http://en.wikipedia.org/wiki/Huffman_coding">Huffman
 * coding on Wikipedia</a>
 * @see <a href="http://en.wikipedia.org/wiki/Run_length_encoding">Run-length
 * encoding on Wikipedia</a>
 * @see <a href="http://www.faqs.org/rfcs/rfc1950.html">RFC 1950 (ZLib)</a>
 * @see <a href="http://www.faqs.org/rfcs/rfc1951.html">RFC 1951 (Deflate)</a>
 * @see <a href="http://www.faqs.org/rfcs/rfc1952.html">RFC 1952 (GZip)</a>
 */
public class InflaterInputStream
   extends InputStream
{
   /** Magic number indicating a ZLib stream was compressed using "deflate." */
   private static final int ZLIB_DEFLATED = 8;
   /** Flag indicating the ZLib stream requires a dictionary. */
   private static final int ZLIB_FDICT = 0x200;
   /** The number of literal/length codes used with fixed Huffman tables. */
   private static final int FIXEDCODES_TABLELEN = 288;
   /** The number of distance codes used with fixed Huffman tables. */
   private static final int FIXEDDISTANCES_TABLELEN = 32;
   /** Longest bit-length allowed for any symbol. */
   private static final int MAXIMUM_CODELEN = 15;
   /** Code word indicating the end of data in a compressed block. */
   private static final int END_OF_DATA_CODE = 256;
   /** First code word corresponding to a Huffman-encoded length. */
   private static final int FIRST_LENGTH_CODE = 257;

   /** Maximum allowable (and default) sliding data window size. */
   private static final int LZ77_WINDOW_MAXLEN = 1 << 15;
   /** Minimum allowable sliding data window size. */
   private static final int LZ77_WINDOW_MINLEN = 1 << 9;

   /** Indicates the current block uses no compression. */
   private static final int MODE_NOCOMPRESSION = 0;
   /** Indicates the current block is compressed using preset Huffman codes. */
   private static final int MODE_FIXEDTABLES = 1;
   /** Indicates the current block uses dynamic Huffman (entropy) codes. */ 
   private static final int MODE_DYNAMICTABLES = 2;
// "The field InflaterInputStream.MODE_UNKNOWN is never read locally"
//   /** Indicates the current block is compressed using an unknown method. */
//   private static final byte MODE_UNKNOWN = 3;

   /** RLE opcode indicating the previous value should be repeated. */
   private static final byte RLE_COPYPREVIOUS = 16;
// "The field InflaterInputStream.RLE_INSERTZERO is never read locally"
//   /** RLE opcode indicating 3-10 zeros should be inserted. */
//   private static final byte RLE_INSERTZERO = 17;
// "The field InflaterInputStream.RLE_REPEATZERO is never read locally"
//   /** RLE opcode indicating 11-138 zeros should be inserted. */
//   private static final byte RLE_REPEATZERO = 18;

   private static final int ADLER_BASE = 65521;
   private static final int ADLER_NMAX = 5552;

   /**
    * Used to decompress dynamic Huffman encoding trees.  These trees may be
    * uniquely defined by the code lengths associated with each symbol in their
    * alphabet, so an array of those lengths precedes the compressed data.  To
    * further save space, the array itself is Huffman encoded and the code lengths
    * for <em>that</em> table precede the compressed array.<p/>
    *
    * The values in <code>CODELEN_RLE_ALPHABET</code> represent the alphabet used
    * to run-length encode the code lengths (for the alphabet used to encode the
    * data).  They're sorted in decreasing order according to probable frequency.
    *
    * @see <a href="http://www.faqs.org/rfcs/rfc1951.html">RFC 1951, §3.2.7</a>
    * @see <a href="http://www.gzip.org/zlib/feldspar.html">An Explanation of the
    * Deflate Algorithm</a> 
    */
   private static final byte[] CODELEN_RLE_ALPHABET = "\20\21\22\0\b\7\t\6\n\5\13\4\f\3\r\2\16\1\17".getBytes();

   /**
    * Used to calculate the base value and extra bits for the various run-length
    * encoding symbols.
    *
    * @see <a href="http://www.faqs.org/rfcs/rfc1951.html">RFC 1951, §3.2.7</a>
    */
   private static final byte[] CODELEN_RLE_BASEEXTRA = "\2\3\7\3\3\13".getBytes();

   /** Used by {@link #reverseBits(int)} to reverse bit patterns. */
   private static final byte[] REVERSE_NYBBLES = "\0\b\4\f\2\n\6\16\1\t\5\r\3\13\7\17".getBytes();

   /**
    * Each character in this string represents the base length associated with
    * one symbol from the length/literal alphabet (257-288).  It's a trick to
    * avoid the byte code overhead of a regular integer array.  It corresponds
    * to an array of shorts like:
    * <pre>
    *    private static final short[] lengthBase =
    *    {
    *         3,   4,   5,   6,   7,   8,   9,  10,  11,  13,  15,  17,
    *        19,  23,  27,  31,  35,  43,  51,  59,  67,  83,  99, 115,
    *       131, 163, 195, 227, 258,
    *    };
    * </pre>
    *
    * @see #m_anLenExtra
    * @see <a href="http://www.faqs.org/rfcs/rfc1951.html">RFC 1951, §3.2.5</a>
    */
   private static final String m_sLenBase = "\3\4\5\6\7\b\t\n\13\r\17\21\23\27\33\37#+3;CScs\203\243\303\343\u0102";

   /**
    * Each character in this string represents the number of extra bits associated
    * with one symbol from the length/literal alphabet (257-288).  It corresponds
    * to an array of bytes like:
    * <pre>
    *    private static final byte[] lengthExtra =
    *    {
    *       0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1,
    *       2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4,
    *       5, 5, 5, 5, 0,
    *    };
    * </pre>
    *
    * @see #m_sLenBase
    * @see <a href="http://www.faqs.org/rfcs/rfc1951.html">RFC 1951, §3.2.5</a>
    */
   private static final byte[] m_anLenExtra = "\0\0\0\0\0\0\0\0\1\1\1\1\2\2\2\2\3\3\3\3\4\4\4\4\5\5\5\5\0".getBytes();

   /**
    * Each character in this string represents the base distance associated with
    * one symbol from the distance alphabet (0-29).  It corresponds to an array of
    * shorts like:
    * <pre>
    *    private static final short[] distanceBase =
    *    {
    *          1,     2,     3,     4,     5,     7,    9,   13,   17,   25,   33,   49,
    *         65,    97,   129,   193,   257,   385,  513,  769, 1025, 1537, 2049, 3073,
    *       4097,  6145,  8193, 12289, 16385, 24577,
    *    };
    * </pre>
    *
    * @see #m_anDistExtra
    * @see <a href="http://www.faqs.org/rfcs/rfc1951.html">RFC 1951, §3.2.5</a>
    */
   private static final String m_sDistBase = "\1\2\3\4\5\7\t\r\21\31\41\61\101\141\201\301\u0101\u0181\u0201\u0301\u0401\u0601\u0801\u0c01\u1001\u1801\u2001\u3001\u4001\u6001";

   /**
    * Each character in this string represents the extra bits associated with one
    * symbol from the distance alphabet (0-29).  It corresponds to an array of
    * bytes like:
    * <pre>
    *    private static final byte[] distanceExtra =
    *    {
    *        0,  0,  0,  0,  1,  1,  2,  2,  3,  3,  4,  4,
    *        5,  5,  6,  6,  7,  7,  8,  8,  9,  9, 10, 10,
    *       11, 11, 12, 12, 13, 13,
    *    };
    * </pre>
    *
    * @see #m_sDistBase
    * @see <a href="http://www.faqs.org/rfcs/rfc1951.html">RFC 1951, §3.2.5</a>
    */     
   private static final byte[] m_anDistExtra = "\0\0\0\0\1\1\2\2\3\3\4\4\5\5\6\6\7\7\b\b\t\t\n\n\13\13\f\f\r\r".getBytes();

   /** The input stream this object decorates. */
   protected InputStream m_InputStream;
   /** <code>true</code> if this stream is wrapped by a ZLib header. */
   private boolean m_bZLibWrapper;

   /** Tiny array used to decompress a single byte. */
   private byte[] m_anByte = new byte[ 1 ];
   /** Small array used to buffer bytes when repositioning stream reads. */
   private byte[] m_anSkip;

   private byte[] m_anIn;              //  stream->next_in
   private int m_nInHead;              //  head of input buffer
   private int m_nInTail;
   private int m_nTotalIn;             //  stream->total_in
   private byte[] m_anOut;             //  stream->next_out
   private int m_nOutTail;
   private int m_nTotalOut;            //  stream->total_out

   private byte[] m_anWindow;          //  state->window
   private int m_nWindowSize;          //  state->wsize
   private int m_nWindowTail;          //  state->write

   private int m_nAccumulator;         //  state->hold
   private int m_nBits;                //  state->bits
   private int m_nAdler;               //  state->adler
   private int m_nCheck;               //  state->check
   private boolean m_bLast;            //  state->last
   private int m_nMode;                //  state->mode
   private int m_nLength;
   private int m_nLiterals;            //  state->nlen
   private int m_nDistance;            //  state->ndist
   private int m_nCodes;               //  state->ncode
   private int m_nCurCode;             //  state->have
   private int m_nLenBase;             //  state->length
   private int m_nDistBase;            //  state->offset
   private int m_nExtraBits;           //  state->extra

   private int[] m_anLengths;          //  state->lens
   private int[] m_anLengthHuff;       //  state->lencode
   private int[] m_anDistanceHuff;     //  state->distcode

   private boolean m_bNeedsDictionary;

   /** The stream header is being parsed. */
   private static final int HEAD      = 0;
   /** The dictionary id is being determined or computed. */
   private static final int DICTID    = 1 + HEAD;
   /** The compression type for the current block is being determined. */
   private static final int TYPE      = 1 + DICTID;
   /** The storage parameters are being determined for an uncompressed block. */
   private static final int STORED    = 1 + TYPE;
   /** An uncompressed block is being copied to the output stream. */
   private static final int COPY      = 1 + STORED;
   /** Huffman parameters are being collected for a compressed block. */
   private static final int TABLE     = 1 + COPY;
   /** The RLE data are being Huffman decoded. */
   private static final int LENLENS   = 1 + TABLE;
   /** The Huffman data are being run-length decoded. */
   private static final int CODELENS  = 1 + LENLENS;
   /** The LZ77 data are being Huffman decoded. */
   private static final int LEN       = 1 + CODELENS;
   /** A base length addend is being computed. */
   private static final int LENEXT    = 1 + LEN;
   /** A base distance is being parsed. */
   private static final int DIST      = 1 + LENEXT;
   /** A base distance addend is being computed. */
   private static final int DISTEXT   = 1 + DIST;
   /** Bytes are being copied from the sliding window to the output stream. */
   private static final int MATCH     = 1 + DISTEXT;
   /** End-of-data has been reached and data integrity is being tested. */
   private static final int CHECK     = 1 + MATCH;
   /** Decompression is complete. */
   private static final int DONE      = 1 + CHECK;


   
   /**
    * Constructor.
    *
    * @param in the stream this object will decorate
    * @param nSize the size of the input buffer associated with this stream
    * @param bWrap <code>true</code> if the compressed data is contained by a ZLib
    * wrapper
    */
   public InflaterInputStream( InputStream in, int nSize, boolean bWrap )
   {
      this( in, new byte[ nSize ], bWrap );
   }

   /**
    * Constructor.  Allows a specific byte array to be used for buffering input, in
    * cases where memory management is handled elsewhere.
    *
    * @param in the stream this object will decorate
    * @param anBuffer the byte array to buffer input
    * @param bWrap <code>true</code> if the compressed data is contained by a ZLib
    * wrapper
    * @throws NullPointerException if <code>in</code> is <code>null</code>
    * @throws IllegalArgumentException if <code>anBuffer</code> is <code>null</code>
    * or zero-length
    */
   public InflaterInputStream( InputStream in, byte[] anBuffer, boolean bWrap )
   {
      if( null == in )
         throw new NullPointerException();
      else if( null == anBuffer || 0 == anBuffer.length )
         throw new IllegalArgumentException( "buffer null or zero length" );

      m_InputStream = in;
      m_anIn = anBuffer;
      m_bZLibWrapper = bWrap;
   }

   /**
    * Decompresses one byte from the input stream.  This method returns -1 if the
    * end of the stream is reached.
    *
    * @return the next decompressed byte
    */
   public int read()
      throws IOException
   {
      return( -1 == read( m_anByte, 0, 1 ) ? -1 : 0xff & m_anByte[ 0 ] );
   }

   /**
    * Fills a buffer with decompressed data, to the limit of its capacity, if
    * possible.  If not (i.e. the last of the compressed bytes have been processed
    * and account for fewer bytes than space remaining in <code>anData</code>),
    * this method returns the actual number of bytes decompressed.
    *
    * @param anData the destination for all decompressed bytes
    * @return the actual number of decompressed bytes copied to the destination
    */
   public int read( byte[] anData )
      throws IOException
   {
      return( read( anData, 0, anData.length ) );
   }

   /**
    * Reads compressed bytes from the stream, decompressing them into the buffer
    * provided.  This method returns the actual number of decompressed bytes
    * written to the buffer, or -1 if the end of file was reached.  "End of file"
    * may include dictionary events on ZLib streams.  In such cases, you may call
    * {@link #getAdler()} to determine the dictionary id.
    *
    * @param anBuffer the destination buffer
    * @param nOffset the first available buffer location
    * @param nLength the number of decompressed bytes desired
    * @return the actual number of decompressed bytes available, or -1 if EOF was
    * encountered or a preset dictionary is required (for ZLib streams)
    * @throws IOException if an error occurred accessing the stream
    * @throws NullPointerException if the destination buffer is <code>null</code>
    * @throws IndexOutOfBoundsException if the offset or length indicate illegal
    * access to the destination buffer
    */
   public int read( byte[] anBuffer, int nOffset, int nLength )
      throws IOException
   {
      if( null == anBuffer )
         throw new NullPointerException();

      if( 0 > (nOffset | nLength | (anBuffer.length - nOffset - nLength)) )
         throw new IndexOutOfBoundsException();

      if( DONE == m_nMode )
         return( -1 );

      m_anOut = anBuffer;
      m_nOutTail = nOffset;
      m_nLength = nLength;

      try
      {
         //  While there's still space to be filled in the output buffer...
         while( 0 < m_nLength )
         {
            int nResult = inflate();

            //  Negative return values (or 0) indicate error or end-of-data conditions.
            if( 0 >= nResult )
            {
               //  If we need more input, try to read bytes from the underlying stream.
               if( m_nInHead >= m_nInTail )
                  fill();
               else
                  break;
            }
         }
      }
      catch( IllegalArgumentException iae )
      {
         String s = iae.getMessage();
         throw new IOException( null == s ? "Invalid ZLIB data format" : s );
      }

      return( m_nOutTail - nOffset );
   }

   /**
    * TODO:  not tested... unit test...
    *
    * Skips forward in the stream the specified number of bytes, provided the
    * value isn't negative.  It refers to the number of decompressed bytes to
    * skip.  This method returns the actual number of bytes skipped, since it's
    * possible to request more than are available.
    *
    * @param nDistance the number of decompressed bytes to skip
    * @return the actual number of bytes skipped 
    */
   public long skip( long nDistance )
      throws IOException
   {
      if( 0 > nDistance )
         throw new IllegalArgumentException( "negative skip length" );

      nDistance = Math.min( nDistance, Integer.MAX_VALUE );
      int nTotal = 0;

      //  Be lazy about initializing this, since most times it's never even used.
      if( null == m_anSkip )
         m_anSkip = new byte[ 512 ];

      //  Decompress bytes until we've gone as far as necessary, or we've gone off
      //  the end of the stream.
      while( nTotal < nDistance )
      {
         int nLength = (int)Math.min( nDistance - nTotal, m_anSkip.length );

         nLength = read( m_anSkip, 0, nLength );
         if( -1 == nLength )
            break;

         nTotal += nLength;
      }

      return( nTotal );
   }

   /**
    * TODO:  document...
    * TODO:  not tested... unit test...
    * @return <code>true</code> if further decompression depends on a preset
    * dictionary
    */
   public boolean needsDictionary()
   {
      return( m_bNeedsDictionary );
   }

   /**
    * TODO:  document...
    * TODO:  not tested... unit test...
    * @return an Adler32 checksum computed on the data decompressed so far, or
    * for the preset dictionary, if required
    */
   public int getAdler()
   {
      return( needsDictionary() ? m_nCheck : m_nAdler );
   }

   /**
    * Attempts to fill the input buffer with bytes from the underlying stream.
    * This method may be called many times by {@link #read(byte[],int,int)} in its
    * effort to satisfy a request for a specific number of decompressed bytes.
    * Remember that the number of bytes read and written may (probably will) be
    * radically different.
    *
    * @throws EOFException if the end of the stream is reached prematurely
    */
   private void fill()
      throws IOException
   {
      m_nInHead = 0;
      m_nInTail = m_InputStream.read( m_anIn, m_nInHead, m_anIn.length );

      if( 0 > m_nInTail )
         throw new EOFException( "Unexpected end of input stream" );
   }

   /**
    * Decompresses bytes from the input buffer to the destination buffer.  This
    * method is state-machine-driven, since processing input bytes is a complex
    * process, especially when working with variable bit-length symbols.  This
    * method must be prepared to exit at almost any moment due to the lack of as
    * little as one bit.<p/>
    *
    * Assuming everything goes as planned and the result is copasetic, this method
    * automatically checksums the decompressed data (if necessary) and updates the
    * bytes-in/bytes-out totals.  This method returns one of
    * <ul>
    *   <li><strong>&lt; 0</strong> &ndash; a recoverable error occured (rare)</li>
    *   <li><strong>0</strong> &ndash; more input data is required</li>
    *   <li><strong>&gt; 0</strong> &ndash; count of bytes successfully</li>
    * decompressed</li>
    * </ul>
    *
    * @return error code (&lt; 0), request for more data (0), or actual number of
    * bytes decompressed
    * @throws IllegalArgumentException if fatal errors are encountered while
    * processing the stream
    */
   private int inflate()
   {
      int nResult = 0;
      int nInSave = m_nInHead;
      int nOutSave = m_nOutTail;

needBits:
      while( true )
      {
         switch( m_nMode )
         {
            case HEAD:
               if( m_bZLibWrapper )
               {
                  //  ZLib preceeds a deflated stream with a simple header specifying compression
                  //  method, flags, and optional dictionary id.
                  if( !haveBits( 16 ) )
                     break needBits;

                  //  Check bits ensure the compression info + flags is a multiple of 31.
                  if( 0 != (((getBits( 8 ) << 8) + (m_nAccumulator >> 8)) % 31) )
                     throw new IllegalArgumentException( "incorrect header check" );

                  //  Verify the compression method is "deflate."
                  if( ZLIB_DEFLATED != getBits( 4 ) )
                     throw new IllegalArgumentException( "unknown compression method" );
                  dropBits( 4 );

                  //  Compute the size of the LZ77 window. It will always be a power of 2, so
                  //  its log-base-2 is specified in the header (less 8). We need to make sure
                  //  the actual size is acceptable.
                  m_nWindowSize = 1 << (8 + getBits( 4 ));

                  //  If the window is too large, it indicates a problem with the data.
                  if( m_nWindowSize > LZ77_WINDOW_MAXLEN )
                     throw new IllegalArgumentException( "invalid window size" );

                  //  If the window is too small, we'll use the default minimum.
                  if( LZ77_WINDOW_MINLEN > m_nWindowSize )
                     m_nWindowSize = LZ77_WINDOW_MINLEN;

                  m_nCheck = updateAdler32( null, 0, 0 );
                  m_nMode = (0 != (ZLIB_FDICT & m_nAccumulator)) ? DICTID : TYPE;
                  clearBits();
               }
               else
               {
                  //  The ZLib wrapper specifies the window size, so if the wrapper isn't present
                  //  we simply allocate the maximum window possible.
                  m_nWindowSize = LZ77_WINDOW_MAXLEN;
                  m_nMode = TYPE;
               }
            break;

            case DICTID:
               if( !haveBits( 32 ) )
                  break needBits;
               m_nAdler = reverseBytes( m_nAccumulator );
               clearBits();

               //  TODO:  when to test for early out?
               m_nMode = TYPE;
            break;

            case TYPE:
               //  If the last block has already been seen, let's get out of here.
               if( m_bLast )
               {
                  alignOnByte();
                  nResult = m_nOutTail - nOutSave;

                  m_nMode = m_bZLibWrapper ? CHECK : DONE;
                  break;
               }
               else if( !haveBits( 3 ) )
                  break needBits;

               //  Check the BFINAL bit to determine if this is the last block.
               m_bLast = (0 != getBits( 1 ));
               dropBits( 1 );

               //  Determine the type of data block.
               switch( getBits( 2 ) )
               {
                  case MODE_NOCOMPRESSION:
                     m_nMode = STORED;
                  break;

                  //  TODO:  not tested... unit test...
                  case MODE_FIXEDTABLES:
                     initFixedTables();
                     m_nMode = LEN;
                  break;

                  case MODE_DYNAMICTABLES:
                     m_nMode = TABLE;
                  break;

                  default:
                     throw new IllegalArgumentException( "invalid block type" );
               }
               dropBits( 2 );
            break;

            case STORED:
               alignOnByte();
               if( !haveBits( 32 ) )
                  break needBits;

               //  Verify the block length.
               m_nLenBase = getBits( 16 );
               dropBits( 16 );

               //  The length is succeeded by its 1s-complement. 
               if( m_nLenBase != (0xffff ^ getBits( 16 )) )
                  throw new IllegalArgumentException( "invalid stored block lengths" );

               clearBits();
               m_nMode = COPY;
            break;

            case COPY:
               if( 0 < m_nLenBase )
               {
                  int nCopy = Math.min( m_nLenBase, Math.min( m_nLength, m_nInTail - m_nInHead ) );
                  if( 0 == nCopy )
                     break needBits;

                  System.arraycopy( m_anIn, m_nInHead, m_anOut, m_nOutTail, nCopy );

                  m_nInHead  += nCopy;
                  m_nOutTail += nCopy;
                  m_nLenBase -= nCopy;
                  m_nLength  -= nCopy;
                  break;
               }
               m_nMode = TYPE;
            break;

            case TABLE:
               if( !haveBits( 14 ) )
                  break needBits;

               //  Count of code lengths for the literal/length alphabet.
               m_nLiterals = getBits( 5 ) + 257;
               dropBits( 5 );

               //  Count of code lengths for the distance alphabet.
               m_nDistance = getBits( 5 ) + 1;
               dropBits( 5 );

               //  Count of code lengths for the code length alphabet.
               m_nCodes = getBits( 4 ) + 4;
               dropBits( 4 );

               m_anLengths = new int[ CODELEN_RLE_ALPHABET.length ];
               m_nCurCode = 0;
               m_nMode = LENLENS;
            break;

            case LENLENS:
               //  Read the 3-bit code lengths for the code length alphabet.
               while( m_nCurCode < m_nCodes )
               {
                  if( !haveBits( 3 ) )
                     break needBits;

                  m_anLengths[ CODELEN_RLE_ALPHABET[ m_nCurCode++ ] ] = (byte)getBits( 3 );
                  dropBits( 3 );
               }

               //  Construct Huffman decoding table for the code length codes.
               m_anLengthHuff = buildHuffmanTable( m_anLengths, 0, m_anLengths.length );
               if( null == m_anLengthHuff )
                  throw new IllegalArgumentException( "invalid code lengths set" );

               m_anLengths = new int[ m_nLiterals + m_nDistance ];
               m_nCurCode = 0;
               m_nMode = CODELENS;
            break;

            case CODELENS:
               //  Decompress the code lengths for the literal/length and distance alphabets.
               while( m_nCurCode < m_anLengths.length )
               {
                  int nCode;

                  while( true )
                  {
                     nCode = m_anLengthHuff[ getBits( 7 ) ];
                     if( (0xf & nCode) <= m_nBits )
                        break;

                     if( !haveBits( 8 ) )
                        break needBits;
                  }

                  int nBits = 0xf & nCode;
                  int nValue = nCode >> 4;

                  //  There are three special code values:
                  //
                  //    RLE_COPYPREVIOUS (16) - duplicate last code 3-6 times
                  //    RLE_INSERTZERO (17) - insert 3-10 zeros
                  //    RLE_REPEATZERO (18) - insert 11-138 zeros
                  //
                  //  All other values (0-15) represent literal code lengths.
                  if( RLE_COPYPREVIOUS > nValue )
                  {
                     if( !haveBits( nBits ) )
                        break needBits;
                     dropBits( nBits );

                     //  Lower values represent literal code lengths in [0, 15].
                     m_anLengths[ m_nCurCode++ ] = (byte)nValue;
                  }
                  else
                  {
                     int nAction = nValue - RLE_COPYPREVIOUS;
                     int nExtraBits = CODELEN_RLE_BASEEXTRA[ nAction ];
                     int nRepeat = CODELEN_RLE_BASEEXTRA[ 3 + nAction ];

                     if( !haveBits( nExtraBits + nBits ) )
                        break needBits;
                     dropBits( nBits );

                     int nLen = 0;
                     if( RLE_COPYPREVIOUS == nValue )
                     {
                        if( 0 == m_nCurCode )
                           throw new IllegalArgumentException( "invalid bit length repeat" );

                        nLen = m_anLengths[ m_nCurCode - 1 ];
                     }

                     nRepeat += getBits( nExtraBits );
                     dropBits( nExtraBits );

                     if( m_nCurCode + nRepeat > m_anLengths.length )
                        throw new IllegalArgumentException( "invalid bit length repeat" );

                     while( 0 < nRepeat-- )
                        m_anLengths[ m_nCurCode++ ] = nLen;
                  }
               }

               m_anLengthHuff = buildHuffmanTable( m_anLengths, 0, m_nLiterals );
               if( null == m_anLengthHuff )
                  throw new IllegalArgumentException( "invalid literal/lengths set" );

               m_anDistanceHuff = buildHuffmanTable( m_anLengths, m_nLiterals, m_nDistance );
               if( null == m_anDistanceHuff )
                  throw new IllegalArgumentException( "invalid distances set" );

               m_nMode = LEN;
            break;

            case LEN:
               if( 0 == m_nLength )
               {
                  nResult = m_nOutTail - nOutSave;
                  break needBits;
               }

               //  Maximum input bits for a length/distance pair is 15 (length code) + 5
               //  (length extra) + 15 (distance code) + 13 (distance extra) = 48 bits. So,
               //  if there are at least 6 bytes available, we can forego checking available
               //  input while decoding.  258 is the theoretical maximum number of bytes that
               //  could be generated by decompressing one symbol, so if we have at least that
               //  much space in the output buffer, we can skip that check, too.
               if( 6 <= m_nInTail - m_nInHead && 258 <= m_nLength )
               {
                  //  TODO:  fast inflation (no bounds checking)...
               }

               //  TODO:  fall through to the slow(er) implementation, for now...
               {
                  int nValue = decodeNextHuffmanSymbol( m_anLengthHuff );
                  if( 0 > nValue )
                     break needBits;

                  if( 0 == (0xff00 & nValue) )
                  {
                     //  8-bit values are byte-literals that get output directly.
                     m_anOut[ m_nOutTail++ ] = (byte)nValue;
                     m_nLength--;
                  }
                  else if( END_OF_DATA_CODE == nValue )
                  {
                     //  Dump these arrays so they'll be garbage collected.
                     m_anLengthHuff = null;
                     m_anDistanceHuff = null;
                     System.gc();

                     //  We're done with this block, so go back for the next one.
                     m_nMode = TYPE;
                     break;
                  }
                  else
                  {
                     nValue -= FIRST_LENGTH_CODE;
                     if( 0 > nValue || 29 < nValue )
                        throw new IllegalArgumentException( "invalid length code" );

                     m_nLenBase = m_sLenBase.charAt( nValue );
                     m_nExtraBits = m_anLenExtra[ nValue ];
                     m_nMode = (0 == m_nExtraBits) ? DIST : LENEXT;
                  }
               }
            break;

            case LENEXT:
               if( !haveBits( m_nExtraBits ) )
                  break needBits;

               m_nLenBase += getBits( m_nExtraBits );
               dropBits( m_nExtraBits );
               m_nMode = DIST;
            break;

            case DIST:
            {
               int nValue = decodeNextHuffmanSymbol( m_anDistanceHuff );
               if( 0 > nValue )
                  break needBits;

               if( 29 < nValue )
                  throw new IllegalArgumentException( "invalid distance code" );

               m_nDistBase = m_sDistBase.charAt( nValue );
               m_nExtraBits = m_anDistExtra[ nValue ];
               m_nMode = (0 == m_nExtraBits) ? MATCH : DISTEXT;
            }
            break;

            case DISTEXT:
               if( !haveBits( m_nExtraBits ) )
                  break needBits;

               m_nDistBase += getBits( m_nExtraBits );
               dropBits( m_nExtraBits );
               m_nMode = MATCH;
            break;

            case MATCH:
               if( 0 != m_nLength )
               {
                  int nFrom;
                  int nCopy = m_nOutTail - nOutSave;
                  
                  //  If the back distance is larger than our output buffer, we need to look at
                  //  our sliding window (which should have been initialized previously).
                  if( m_nDistBase > nCopy )
                  {
                     nCopy = m_nDistBase - nCopy;
                     if( nCopy > m_nWindowTail )
                     {
                        nCopy -= m_nWindowTail;
                        nFrom = m_nWindowSize - nCopy;
                     }
                     else
                        nFrom = m_nWindowTail - nCopy;

                     nCopy = Math.min( nCopy, Math.min( m_nLength, m_nLenBase ) );
                     m_nLength -= nCopy;
                     m_nLenBase -= nCopy;
                     
                     System.arraycopy( m_anWindow, nFrom, m_anOut, m_nOutTail, nCopy );
                     m_nOutTail += nCopy;
                  }
                  else
                  {
                     nFrom = m_nOutTail - m_nDistBase;
                     nCopy = Math.min( m_nLength, m_nLenBase );
                     
                     m_nLength -= nCopy;
                     m_nLenBase -= nCopy;
                     
                     //  TODO:  optimize with System.arraycopy(), noting overlap issues...
                     while( 0 < nCopy-- )
                        m_anOut[ m_nOutTail++ ] = m_anOut[ nFrom++ ];
                  }

                  //  If we've completely expanded the symbol, go back for more instructions.
                  if( 0 == m_nLenBase )
                     m_nMode = LEN;
               }
               else
               {
                  //  We've output as many bytes as were requested, so we're done.
                  nResult = m_nOutTail - nOutSave; 
                  break needBits;
               }
            break;

            case CHECK:
               if( haveBits( 32 ) )
               {
                  //  The last buffer-full needs to be added to the checksum.
                  if( 0 < nResult )
                  {
                     updateAdler32( m_anOut, nOutSave, nResult );
                     
                     m_nTotalOut += nResult;
                     nOutSave = m_nOutTail;
                  }

                  //  Verify the data integrity.
                  if( m_nAdler != reverseBytes( getBits( 32 ) ) )
                     throw new IllegalArgumentException( "incorrect data check" );
                  
                  clearBits();
                  m_nMode = DONE;
               }
            break;

            case DONE:
               //  If we're done, ignore space remaining in the output buffer. 
               m_nLength = 0;
            break needBits;

            default:
            break;
         }
      }

      //  If any bytes were actually decompressed, perform some additional processing.
      if( m_nOutTail != nOutSave )
      {
         //  Update the sliding window (as long as this isn't the last time we expect to
         //  be called for this stream).
         if( CHECK > m_nMode )
            copyToWindow( nOutSave );

         //  ZLib streams are checksummed to ensure their integrity.
         if( m_bZLibWrapper )
            updateAdler32( m_anOut, nOutSave, m_nOutTail - nOutSave );
      }

      m_nTotalIn += (m_nInHead - nInSave);
      m_nTotalOut += (m_nOutTail - nOutSave);

      return( nResult );
   }

   /**
    * Copies recently decompressed bytes from the output buffer to a "sliding
    * window" buffer, preserving a history from which to lift repeated strings.  The
    * size of the buffer depends on a setting found in the ZLib headers, or, if none
    * are present, defaults to 32k.  The buffer is circular, so a cursor keeps track
    * of the current write position.
    * 
    * @param nStart the offset into <code>m_anOut</code> from which to start the
    * copy (<code>m_nOutTail</code> is the implicit ending offset)
    */
   private void copyToWindow( int nStart )
   {
      //  If the sliding window doesn't exist yet, allocate it.
      if( null == m_anWindow )
         m_anWindow = new byte[ m_nWindowSize ];

      int nAvail = m_nWindowSize - m_nWindowTail;
      int nCopy = m_nOutTail - nStart;

      //  If we're being asked to copy bytes across the window's discontinuity, we'll
      //  need to break the operation into two steps.
      if( nCopy > nAvail )
      {
         //  Copy as many bytes as will fit in the remaining window space.
         System.arraycopy( m_anOut, nStart, m_anWindow, m_nWindowTail, nAvail );
         m_nWindowTail = 0;

         //  Update our totals before completing the copy in the next step.
         nStart += nAvail;
         nCopy -= nAvail;
      }

      //  Copy bytes from the output buffer to the sliding window, updating the
      //  position of the cursor into the circular buffer.
      System.arraycopy( m_anOut, nStart, m_anWindow, m_nWindowTail, nCopy );
      m_nWindowTail += nCopy;
   }

   /**
    * Reconstitutes a Huffman encoding table from an array of code lengths.  The
    * Deflate algorithm imposes two important restrictions on the Huffman codes
    * that a compliant compressor may generate:
    * <ul>
    * <li>All codes of a given bit length have lexicographically consecutive
    * values, in the same order as the symbols they represent;</li>
    * <li>Shorter codes lexicographically precede longer codes.</li>
    * </ul><p/>
    *
    * Given these rules, we can build the Huffman encoding table with nothing more
    * than the code lengths for each symbol of the alphabet.<p/>
    *
    * Note that this method is all hand-waving to me, since I lifted it from some
    * online resource.  I couldn't find anything similar in any of the ZLib
    * implementations I checked (quite a few), but it comes in considerably
    * shorter than all of them.  The down side (besides not understanding how it
    * works, exactly), is that it repeats table entries so they always total 512,
    * despite the fact that far fewer are actually needed, in general.
    *
    * @param anLengths an array of code lengths, in order, one for each symbol of
    * the compressed alphabet
    * @param nOffset the start of symbols in the buffer
    * @param nLength the number of symbols represented by the lengths
    * @return a table of shorts comprising the Huffman encoding (now decoding)
    * @see #decodeNextHuffmanSymbol(short[])
    */
   private static int[] buildHuffmanTable( int[] anLengths, int nOffset, int nLength )
   {
      int[] anCounts = new int[ 1 + MAXIMUM_CODELEN ];
      int[] anOffsets = new int[ 1 + MAXIMUM_CODELEN ];
      int nCodes = nLength;

      //  Perform a frequency count of the code lengths.  This assumes that every
      //  length is less than MAXBITS (15).
      while( 0 < nCodes-- )
         anCounts[ anLengths[ nOffset++ ] ]++;

      int nStart = 0;
      int nUpper = 512;

      for( int i = 1; i <= MAXIMUM_CODELEN; i++ )
      {
         anOffsets[ i ] = nStart;
         nStart += anCounts[ i ] << (16 - i);

         if( i > 9 )
         {
            int k4 = nStart & 0xfff80;
            int i5 = anOffsets[ i ] & 0xfff80;
            nUpper += (k4 - i5) >> (16 - i);
         }
      }

      int[] anTable = new int[ nUpper ];
      if( null != anTable )
      {
         int nBound = 512;
         for( int i = MAXIMUM_CODELEN; i > 9; i-- )
         {
            int l4 = nStart & 0xfff80;
            nStart -= anCounts[ i ] << (16 - i);
            int j5 = nStart & 0xfff80;
   
            for( int j = j5; j < l4; j += 128 )
            {
               anTable[ reverseBits( j ) ] = (short)(-nBound << 4 | i);
               nBound += 1 << (i - 9);
            }
         }

         nOffset -= nLength;
         for( int i = 0; i < nLength; i++ )
         {
        	int nCodeLen = anLengths[ nOffset++ ];
            if( 0 == nCodeLen )
               continue;
   
            int nCurOffset = anOffsets[ nCodeLen ];
            int nIdx = reverseBits( nCurOffset );
   
            if( nCodeLen <= 9 )
            {
               do
               {
                  anTable[ nIdx ] = (i << 4 | nCodeLen);
                  nIdx += (1 << nCodeLen);
               }
               while( nIdx < 512 );
            }
            else
            {
               int nEntry = anTable[ 0x1ff & nIdx ];
               int nMaximum = 1 << (0xf & nEntry);
               
               nEntry = -(nEntry >> 4);
               do
               {
                  anTable[ nEntry | nIdx >> 9 ] = (short)(i << 4 | nCodeLen);
                  nIdx += (1 << nCodeLen);
               }
               while( nIdx < nMaximum );
            }
            
            anOffsets[ nCodeLen ] = nCurOffset + (1 << 16 - nCodeLen);
         }
      }

      return( anTable );
   }

   /**
    * Decodes the next symbol from the input stream (via the <code>m_anIn</code>
    * buffer) using the Huffman table specified.  This method returns -1 if more
    * data bits are required to decode the symbol.<p/>
    *
    * Like {@link #buildHuffmanTable(byte[],int,int)}, I found the body of this
    * method online.  It isn't quite so inscrutable, especially since I simplified
    * it greatly, compared to the original.  
    *
    * @param anTable the Huffman table used to decode the symbol
    * @return the alphabet value represented by the Huffman code, or -1 if more
    * data bits are required to identify the symbol
    */
   private int decodeNextHuffmanSymbol( int[] anTable )
   {
      int nBits = haveBits( 9 ) ? 9 : m_nBits;
      int nCode = anTable[ getBits( nBits ) ];
      int nValue = 0xf & nCode;

      //  If we know input bits are no problem, we can check the code to see if it
      //  corresponds to a subtable.
      if( 9 == nBits )
      {
         //  A negative code means the upper bits specify a subtable within the larger
         //  Huffman table we're using to decode symbols.
         if( 0 > nCode )
         {
            int nSubTable = -(nCode >> 4);
            
            nBits  = haveBits( nValue ) ? nValue : m_nBits; 
            nCode  = anTable[nSubTable | getBits( nBits ) >> 9];
            nValue = 0xf & nCode;
            
            if( nValue > nBits )
               nValue = -1;
         }
      }
      else if( 0 > nCode || nValue > nBits )
      {
         //  Didn't have enough input bits to fully decode the symbol, so let our caller
         //  know they need to go back for more.
         nValue = -1;
      }
      
      if( 0 <= nValue )
      {
         dropBits( nValue );
         nValue = nCode >> 4;
      }
      
      return( nValue );
   }

   /**
    * Drop enough bits from the accumulator to reach the next whole-byte boundary.
    * When this method returns, the accumulator will be aligned on a physical byte
    * from the input buffer.
    */
   private void alignOnByte()
   {
      dropBits( 7 & m_nBits );
   }
   
   /**
    * Reset the bit accumulator, discarding any bits available there.  This method
    * effectively aligns on a whole-byte boundary as well, since bytes from the
    * input buffer must be accumulated before bits will be available again. 
    */
   private void clearBits()
   {
      m_nAccumulator = 0;
      m_nBits = 0;
   }

   /**
    * Returns <code>true</code> if there are enough bits in the accumulator to
    * satisfy a request for a bit-string of the specified length.  Before returning
    * <code>false</code>, however, this method will pull additional bytes from the
    * input buffer, if available.
    *
    * @param n the bit-string length required, must be &lt;= 32
    * @return <code>true</code> if enough bits have been accumulated
    * @see #getBits(int)
    */
   private boolean haveBits( int n )
   {
      //  Pull bytes from the input buffer until we have enough bits, or we run out.
      while( m_nBits < n && m_nInTail > m_nInHead )
      {
          m_nAccumulator |= (0xff & m_anIn[ m_nInHead++ ]) << m_nBits;
          m_nBits += 8;
      }

      return( m_nBits >= n );
   }

   /**
    * Returns the least significant bit-string of length <code>n</code> from the
    * bit accumulator.  The accumulator is left unchanged.  The return value of
    * this method is undefined if not enough bits are available.
    *
    * @param n the number of bits to pull from the bottom of the accumulator
    * @return a bit-string of the specified length
    * @see #haveBits(int)
    */
   private int getBits( int n )
   {
      //  Java is "helping" us by shifting by n mod 32 (n % 32), so we have to use a
      //  long value (bumping the modulo operation to 64 bits) then downcast.  If we
      //  don't do this, any attempt to grab all 32 bits will result in a value 0.
      return( (int)(((1L << n) - 1) & m_nAccumulator) );
   }

   /**
    * Removes bits from the bottom of the accumulator.
    *
    * @param n the number of bits to remove
    */
   private void dropBits( int n )
   {
      m_nAccumulator >>>= n;
      m_nBits -= n;
   }

   /**
    * Switches the "endianness" of an integer.  Big-endian integers, which are
    * stored in network byte order (most significant byte first), are converted to
    * little-endian and vice versa.  That is, this method reverses the order of the
    * bytes in a number.<p/>
    *
    * In contrast, {@link #reverseBits(int)} reverses the <em>bit</em> order of a
    * number.
    *  
    * @param n the integer to convert
    * @return the same value with its bytes in reverse order
    */
   private static int reverseBytes( int n )
   {
      return( ((0xff000000 & n) >>> 24) +
              ((0x00ff0000 & n) >>   8) +
              ((0x0000ff00 & n) <<   8) +
              ((0x000000ff & n) <<  24) );
   }

   /**
    * Reverses (left to right) the pattern in the lower 16 bits of an integer.  Bits
    * in the upper 16 are discarded.
    *
    * @param nValue the value to reverse
    * @return the lower 16 bits of <code>nValue</code>, in reverse order
    */
   private static int reverseBits( int nValue )
   {
       return( REVERSE_NYBBLES[ (nValue >>  0) & 0xf ] << 12 |
               REVERSE_NYBBLES[ (nValue >>  4) & 0xf ] <<  8 |
               REVERSE_NYBBLES[ (nValue >>  8) & 0xf ] <<  4 |
               REVERSE_NYBBLES[ (nValue >> 12) & 0xf ] );
   }

   /**
    * Constructs two Huffman decoding tables representing a fixed set of codes set
    * out in the published "deflate" documentation, RFC 1951.<p/>
    * 
    * We implement "lazy" initialization of these tables, since they occupy 1,024
    * bytes each and may never even be needed.  Keep in mind, however, that they
    * will be recreated for every "block" in the source data compressed with fixed
    * Huffman codes.  This seems like a reasonable trade-off since the expected
    * frequency of fixed code use is low&mdash;most data will be compressed using
    * dynamic Huffman codes&mdash;and the average number of such blocks in any one
    * file approaches 1.  Generating the tables themselves is relatively fast,
    * anyway.
    *
    * @see <a href="http://www.faqs.org/rfcs/rfc1951.html">RFC 1951, Deflate</a>
    */
   private void initFixedTables()
   {
      int i = 0;
      m_anLengths = new int[ FIXEDCODES_TABLELEN ];

      //  Build an array of code lengths, according to §3.2.6 of RFC 1951. 
      while( i < 144 )
         m_anLengths[ i++ ] = 8;

      while( i < 256 )
         m_anLengths[ i++ ] = 9;

      while( i < 280 )
         m_anLengths[ i++ ] = 7;

      while( i < FIXEDCODES_TABLELEN )
         m_anLengths[ i++ ] = 8;

      //  Decode the literal/length codes.
      m_anLengthHuff = buildHuffmanTable( m_anLengths, 0, FIXEDCODES_TABLELEN );

      //  The distance codes all have the same length.
      for( i = 0; i < FIXEDDISTANCES_TABLELEN; i++ )
         m_anLengths[ i ] = 5;

      //  Decode the distance codes.
      m_anDistanceHuff = buildHuffmanTable( m_anLengths, 0, FIXEDDISTANCES_TABLELEN );
   }

   /**
    * Computes the Adler32 checksum for a buffer of data.  A previously computed
    * value may be passed in for incremental updates.<p/>
    * 
    * Adler32 is an improved, 32-bit version of Fletcher's checksum.
    *
    * @param anBuffer the data buffer to checksum
    * @param nOffset the first buffer byte to consider
    * @param nLength the number of buffer bytes to consider
    * @return a 32-bit checksum
    */
   protected int updateAdler32( byte[] anBuffer, int nOffset, int nLength )
   {
      if( null == anBuffer )
         m_nAdler = 1;
      else if( 0 < nLength )
      {
         int s1 = m_nAdler & 0xffff;
         int s2 = (m_nAdler >> 16) & 0xffff;

         //  We could process all the bytes in the buffer with a simple loop (and many
         //  implementations do) such as the following:
         //
         //    for( int i = 0; i < nLength; i++ )
         //    {
         //       s1 = (s1 + anBuffer[ i ]) % ADLER_BASE;
         //       s2 = (s2 + s1) % ADLER_BASE;
         //    }
         //
         //  The loop below follows Mark Adler's original implementation, which chunks
         //  the buffer to minimize the number of modulo operations necessary.  It's
         //  questionable whether this improves performance under J2ME, though, since
         //  the extra byte code processing may overtake the expensive divisions (in
         //  every iteration of the loop above).  (Are today's divisions expensive?)
         while( 0 < nLength )
         {
            int k = Math.min( ADLER_NMAX, nLength );
            nLength -= k;

            while( 0 < k-- )
            {
               s1 += (0xff & anBuffer[ nOffset++ ]);
               s2 += s1;
            }

            s1 %= ADLER_BASE;
            s2 %= ADLER_BASE;
         }

         m_nAdler = (s2 << 16) + s1;
      }

      return( m_nAdler );
   }
}
