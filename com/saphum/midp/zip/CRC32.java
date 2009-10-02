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



/**
 * This class implements the
 * <a href="http://en.wikipedia.org/wiki/CRC32">CRC-32</a> (Cyclical Redundancy
 * Check) checksum.
 *
 * @author Peter Heinrich
 */
public class CRC32
{
   /**
    * The generator polynomial used to calculate the checksum.  The value here
    * corresponds to the following polynomial:
    * <pre>
    *   x^32 + x^26 + x^23 + x^16 + x^12 + x^11 +
    *                   x^10 + x^8 + x^7 + x^5 + x^4 + x^2 + x^1 + x^0
    * </pre>
    * This particular polynomial uniquely identifies the CRC-32 algorithm.
    *
    * @see #initRemainderTable(int[],int)
    */
   public static final int DEFAULT_POLYNOMIAL = 0x04c11db7;

   /** The size of the lookup table we create for performance reasons. */
   private static final int REMAINDER_TABLELEN = 1 << 8;

   /** How much we have to shift an int to access its high byte. */
   private static final int HIGHBYTE_OFFSET = 24;
   /** Used to strip off an int's most significant bit. */
   private static final int HIGHBIT_MASK = 0x80000000;
   /** Used to strip off a long's lower 32 bits. */
   private static final long LOWINT_MASK = 0x00000000ffffffff;

   /** A table of CRC remainders, one for every possible byte value. */
   private int[] m_anRemainders;
   /** The current running checksum. */
   private int m_nCRC;



   /**
    * Constructor.
    */
   public CRC32()
   {
      this( null );
   }

   /**
    * Constructor which allows remainder specification.  This version of the
    * constructor is handy when we want to share a remainder table (which occupies
    * 1,024 bytes) or change the polynomial used to generate it.  If this parameter
    * is <code>null</code>, the object generates a private table with the standard
    * generator polynomial for the CRC-32 algorithm.
    *
    * @param anRemainders a table of precomputed remainders
    * @see #initRemainderTable(int[],int)
    * @see #DEFAULT_POLYNOMIAL
    * @throws IllegalArgumentException if the specified table isn't the correct
    * length (256 entries) 
    */
   public CRC32( int[] anRemainders )
   {
      if( null == anRemainders )
      {
         anRemainders = new int[ REMAINDER_TABLELEN ];
         initRemainderTable( anRemainders, DEFAULT_POLYNOMIAL );
      }
      else if( REMAINDER_TABLELEN != anRemainders.length )
         throw new IllegalArgumentException();

      m_anRemainders = anRemainders;
   }

   /**
    * Populates a table with the integer remainders used by the CRC algorithm.
    * This method is called by the default (zero-argument) constructor, but it's
    * public in case we want to change the details of the generating polynomial
    * or handle memory management for the table elsewhere.<p/>
    *
    * The generating polynomial is specified in network byte order, with each bit
    * representing a term in the corresponding position.  That is, bit 0
    * corresponds to x^0, bit 8 corresponds to x^8, etc.  Note that x^32 is
    * always included in the calculation.  
    *
    * @param anRemainders the destination array
    * @param nPolynomial the generating polynomial
    * @throws IllegalArgumentException if the specified table isn't the correct
    * length (256 entries)
    */
   public static void initRemainderTable( int[] anRemainders, int nPolynomial )
   {
      if( REMAINDER_TABLELEN != anRemainders.length )
         throw new IllegalArgumentException();

      //  Compute the CRC remainder for every possible byte value.
      for( int i = 0; i < anRemainders.length; i++ )
      {
         int nRemainder = i << HIGHBYTE_OFFSET;

         //  Process each bit in the current byte value.
         for( int j = 0; j < 8; j++ )
         {
            //  Compute the remainder bit in this position based on whether or not it's
            //  already set.
            if( 0 == (HIGHBIT_MASK & nRemainder) )
               nRemainder <<= 1;
            else
               nRemainder = (nRemainder << 1) ^ nPolynomial;
         }

         //  Store the result for this byte.
         anRemainders[ i ] = nRemainder;
      }
   }

   /**
    * Recomputes the checksum given the additional byte specified.
    *
    * @param data the byte to add to the checksum
    */
   public void update( int data )
   {
      m_nCRC = (m_nCRC << 8) ^ m_anRemainders[ (m_nCRC >>> HIGHBYTE_OFFSET) ^ (0xff & data) ];
   }

   /**
    * Recomputes the checksum given the additional bytes specified by the array.
    * 
    * @param anData the bytes to add to the checksum
    */
   public void update( byte[] anData )
   {
      update( anData, 0, anData.length );
   }

   /**
    * Recomputes the checksum given the additional bytes specified by the array.
    * Only <code>nLength</code> bytes are processed, starting at the offset
    * indicated.
    *
    * @param anData the bytes to add to the checksum
    * @param nOffset the starting offset of the first significant byte
    * @param nLength the number of bytes to process
    * @throws IndexOutOfBoundsException if the offset or length are less than 0, or
    * if they indicate bytes off the end of the array
    */
   public void update( byte[] anData, int nOffset, int nLength )
   {
      if( 0 > (nOffset | nLength | anData.length - nOffset - nLength) )
         throw new IndexOutOfBoundsException();
 
      while( 0 < nLength-- )
         m_nCRC = (m_nCRC << 8) ^ m_anRemainders[ (m_nCRC >>> HIGHBYTE_OFFSET) ^ (0xff & anData[ nOffset++ ]) ];
   }

   /**
    * Resets the checksum to 0.
    */
   public void reset()
   {
      m_nCRC = 0;
   }

   /**
    * Returns the current value of the checksum.
    *
    * @return the current value of the checksum
    */
   public long getValue()
   {
      //  Only the lower 32 bits are significant.
      return( LOWINT_MASK & m_nCRC );
   }
}
