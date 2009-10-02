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



package com.saphum.midp.zip;



import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;



/**
 * This class is a J2ME translation of the java.util.zip.GZIPInputStream class
 * found in the Java 2 Standard Edition.  It's simply a wrapper around an
 * {@link com.saphum.zip.InflaterInputStream} object, providing stream access
 * and GZIP header validation.
 *
 * @author Peter Heinrich
 * @see <a href="http://www.faqs.org/rfcs/rfc1952.html">RFC 1952, GZIP</a>
 */
public class GZIPInputStream
   extends InflaterInputStream
{
   /** Axiomatic, but good to formalize when bit twiddling. */
   private static final int BITS_IN_BYTE = 8;
// "The field GZIPInputStream.BITS_IN_SHORT is never read locally"
//   /** Axiomatic, but good to formalize when bit twiddling. */
//   private static final int BITS_IN_SHORT = BITS_IN_BYTE << 1;
   /** Used to mask off the lower 16 bits of an integer. */
   private static final int LOWWORD_MASK = 0xffff;

   /** A magic number that identifies a GZIP header. */
   private static final int GZIP_MAGIC = 0x1f8b;
   /** Specifies the total size of certain unused header fields. */
   private static final int MTIMEXFLOS_FIELDLEN = 6;

   /** Value to indicate the "deflate" compression method was used. */
   private static final int CM_DEFLATE = 8;

// "The field GZIPInputStream.FLG_FTEXT is never read locally"
//   /** Set if stream is likely ASCII text.  For completeness; unused here. */
//   private static final byte FLG_FTEXT = 1;
   /** Set if a CRC-16 header checksum immediately preceeds the compressed data. */
   private static final int FLG_FHCRC = 2;
   /** Set if "extra" fields are present in the header. */
   private static final int FLG_FEXTRA = 4;
   /** Set if the original filename is present (it will be NUL-terminated). */
   private static final int FLG_FNAME = 8;
   /** Set if a text comment appears in the header. */
   private static final int FLG_FCOMMENT = 16;

   /** Checksum object used to verify the integrity of the GZIP data. */ 
   private final CRC32 m_CRC = new CRC32();



   /**
    * Constructor.  Creates a new stream filter to decompress deflated data
    * encapsulated in a GZIP wrapper.
    *
    * @param in the input stream to decorate
    * @param size how many bytes to set aside for buffering the input
    * @throws IOException if an I/O error occurs or the GZIP header is invalid
    */
   public GZIPInputStream( InputStream in, int size )
      throws IOException
   {
      this( in, new byte[ size ] );
   }

   /**
    * Constructor.  Creates a new stream filter to decompress deflated data
    * encapsulated in a GZIP wrapper.  Allows a specific byte array to be used to
    * be used for buffering input, in cases where memory management is handled
    * elsewhere.
    *
    * @param in the input stream to decorate
    * @param anBuffer the byte array to buffer input
    * @throws IOException if an I/O error occurs or the GZIP header is invalid
    */
   public GZIPInputStream( InputStream in, byte[] anBuffer )
      throws IOException
   {
      super( in, anBuffer, false );

      validateHeader();
      m_CRC.reset();
   }

   /**
    * Attempts to fill the specified buffer with decompressed bytes.  The actual
    * number of bytes decompressed into the buffer is returned.
    *
    * @param anData the data buffer to fill
    * @param nOffset where in the buffer to start filling
    * @param nLength the number of buffer locations to fill
    * @return the actual number of buffer locations filled
    * @throws IOException if an I/O error occurs or if the GZIP trailer is
    * invalid (when the end of stream is reached)
    */
   public int read( byte[] anData, int nOffset, int nLength )
      throws IOException
   {
      nLength = super.read( anData, nOffset, nLength );
      if( 0 == nLength )
         validateFooter();
      else if( 0 < nLength )
         m_CRC.update( anData, nOffset, nLength );

      return( nLength );
   }

   /**
    * Reads the GZIP header and verifies it isn't corrupt.  This can occur if the
    * data isn't in GZIP format or the wrong compression method is used, as well as
    * the result of genuine data corruption.
    *
    * @throws IOException if an I/O error occurs or the the header data is corrupt
    */
   private void validateHeader()
      throws IOException
   {
      int val = nextUnsignedShort();
      
      //  Make sure the stream data is in GZIP format.
      if( GZIP_MAGIC != val )
         throw new IOException( "Not in GZIP format" );

      //  Make sure the stream data was compressed using deflate compression.
      if( CM_DEFLATE != nextUnsignedByte() )
         throw new IOException( "Unsupported compression method" );

      //  Read the flags byte and skip the MTIME, XFL, and OS fields.
      int nFlags = nextUnsignedByte();
      skipBytes( MTIMEXFLOS_FIELDLEN );

      //  Skip the optional "extra" field, if present.
      if( 0 != (FLG_FEXTRA & nFlags) )
         skipBytes( nextUnsignedShort() );

      //  Skip the optional filename, which will be NUL-terminated if present.
      if( 0 != (FLG_FNAME & nFlags) )
         while( 0 != nextUnsignedByte() )
            /* skip to NUL terminator */;

      //  Skip the optional comment, which will be NUL-terminated if present.
      if( 0 != (FLG_FCOMMENT & nFlags) )
         while( 0 != nextUnsignedByte() )
            /* skip to NUL terminator */;

      //  Verify the CRC, if present.  The value is a CRC-16, which is basically just
      //  the lower 16 bits of a regular CRC-32.
      if( 0 != (FLG_FHCRC & nFlags) && (LOWWORD_MASK & m_CRC.getValue()) != nextUnsignedShort() )
         throw new IOException( "Corrupt GZIP header" );
   }

   /**
    * Reads the GZIP trailer and verifies it.  The trailer consists of a simple
    * CRC-32 checksum followed by a file length.  It's tricky to check because we
    * may need to pull decompressed-but-as-yet-unprocessed bytes from our work
    * buffer, as well as raw bytes from the underlying stream.
    *
    * @throws IOException if an I/O error occurs or the trailer appears corrupt
    */
   private void validateFooter()
//      throws IOException
   {
      // TODO: the actual validation...
   }

   /**
    * Returns an unsigned byte from the specified stream.  Only eight bits are
    * read but the value is promoted to an integer.  The value is automatically
    * added to the running CRC-32 checksum maintained by this object.
    *
    * Note that this method does no decompression&mdash;it reads raw bytes from the
    * underlying stream.  This is mainly to read data in the stream header, which
    * never compressed.
    *
    * @return an unsigned value in the range [0, 255]
    * @throws IOException if an I/O error occurs or the stream's end is reached
    */
   private int nextUnsignedByte()
      throws IOException
   {
      int b = m_InputStream.read();
      if( -1 == b )
         throw new EOFException();

      m_CRC.update( b );
      return( b );
   }

   /**
    * Returns an unsigned short from the specified stream.  Only sixteen bits are
    * read but the value is promoted to an integer.  The value is automatically
    * added to the running CRC-32 checksum maintained by this object.<p/>
    *
    * This method uses <code>nextUnsignedByte()</code> to read two bytes in network
    * order and combines them to form a 16-bit value.
    *
    * @return an unsigned value in the range [0, 65535]
    * @throws IOException if an I/O error occurs or the stream's end is reached
    * @see #nextUnsignedByte()
    */
   private int nextUnsignedShort()
      throws IOException
   {
      return( (nextUnsignedByte() << BITS_IN_BYTE) | nextUnsignedByte() );
   }

// "The method nextUnsignedInteger() from the type GZIPInputStream is never used locally"
//   /**
//    * Returns an unsigned integer from the specified stream.  Only thirty-two bits
//    * are read but the value is promoted to a long.  The value is automatically
//    * added to the running CRC-32 checksum maintained by this object.<p/>
//    *
//    * This method uses <code>nextUnsignedShort()</code> to read two short values in
//    * network order and combines them to form a 32-bit value.
//    *
//    * @return an unsigned value in the range [0, 4294967296]
//    * @throws IOException if an I/O error occurs or the stream's end is reached
//    * @see #nextUnsignedShort()
//    */
//   private long nextUnsignedInteger()
//      throws IOException
//   {
//      return( ((long)nextUnsignedShort() << BITS_IN_SHORT) | nextUnsignedShort() );
//   }

   /**
    * Skips bytes of input data.  This method pulls raw bytes from the underlying
    * stream one at time (instead of seeking) in order to maintain a running
    * checksum of the bytes it processes. 
    *
    * @param nCount the number of bytes to skip
    * @throws IOException if an I/O error occurs or the stream's end is reached
    */
   private void skipBytes( int nCount )
      throws IOException
   {
      //  Checksum each byte as it passes by us.
      while( 0 < nCount-- )
         nextUnsignedByte();
   }
}
