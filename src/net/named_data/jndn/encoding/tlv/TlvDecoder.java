/**
 * Copyright (C) 2014 Regents of the University of California.
 * @author: Jeff Thompson <jefft0@remap.ucla.edu>
 * See COPYING for copyright and distribution information.
 */

package net.named_data.jndn.encoding.tlv;

import java.nio.ByteBuffer;
import net.named_data.jndn.encoding.EncodingException;

/**
 * A TlvDecoder has methods to decode an input according to NDN-TLV.
 */
public class TlvDecoder {
  /**
   * Create a new TlvDecoder to decode the input.
   * @param input The input ByteBuffer whose position and limit are set to the 
   * desired bytes to decode. This calls input.duplicate(), but does not copy 
   * the underlying buffer whose contents must remain valid during the life of 
   * this object.
   */
  public 
  TlvDecoder(ByteBuffer input)
  {
    input_ = input.duplicate();        
  }
  
  /**
   * Decode a VAR-NUMBER in NDN-TLV and return it. Update the input buffer 
   * position.
   * @return The decoded VAR-NUMBER as a Java 32-bit int.
   * @throws EncodingException if the VAR-NUMBER is 64-bit.
   */
  public final int
  readVarNumber() throws EncodingException
  {
    int firstOctet = (int)input_.get() & 0xff;
    if (firstOctet < 253)
      return firstOctet;
    else
      return readExtendedVarNumber(firstOctet);
  }
    
  /**
   * A private function to do the work of readVarNumber, given the firstOctet
   * which is >= 253. Update the input buffer position.
   * @param firstOctet The first octet which is >= 253, used to decode the 
   * remaining bytes.
   * @return The decoded VAR-NUMBER as a Java 32-bit int.
   * @throws EncodingException if the VAR-NUMBER is 64-bit.
   */
  private int
  readExtendedVarNumber(int firstOctet) throws EncodingException
  {
    // This is a private function so we know firstOctet >= 253.
    if (firstOctet == 253)
      return (((int)input_.get() & 0xff) << 8) +
              ((int)input_.get() & 0xff);
    else if (firstOctet == 254)
      return (((int)input_.get() & 0xff) << 24) +
             (((int)input_.get() & 0xff) << 16) +
             (((int)input_.get() & 0xff) << 8) +
              ((int)input_.get() & 0xff);
    else
      // We are returning a 32-bit int, so can't handle 64-bit.
      throw new EncodingException
        ("Decoding a 64-bit VAR-NUMBER is not supported");
  }
  
  /**
   * Decode the type and length from this's input starting at the input buffer 
   * position, expecting the type to be expectedType and return the length. 
   * Update the input buffer position. Also make sure the decoded length does 
   * not exceed the number of bytes remaining in the input.
   * @param expectedType The expected type as a 32-bit Java int.
   * @return The length of the TLV as a 32-bit Java int.
   * @throws EncodingException if did not get the expected TLV type, or the TLV 
   * length exceeds the buffer length, or the type is encoded as a 64-bit value,
   * or the length is encoded as a 64-bit value.
   */
  public final int
  readTypeAndLength(int expectedType) throws EncodingException
  {
    int type = readVarNumber();
    if (type != expectedType)
      throw new EncodingException("Did not get the expected TLV type");

    int length = readVarNumber();
    if (length > input_.remaining())
      throw new EncodingException("TLV length exceeds the buffer length");

    return length;
  }
  
  /**
   * Decode the type and length from the input starting at the input buffer 
   * position, expecting the type to be expectedType. Update the input buffer 
   * position. Also make sure the decoded length does not exceed the number of 
   * bytes remaining in the input. Return the input buffer position (offset) of 
   * the end of this parent TLV, which is used in decoding optional nested TLVs. 
   * After reading all nested TLVs, you should call finishNestedTlvs.
   * @param expectedType The expected type as a 32-bit Java int.
   * @return The input buffer position (offset) of the end of the parent TLV.
   * @throws EncodingException if did not get the expected TLV type, or the TLV 
   * length exceeds the buffer length, or the type is encoded as a 64-bit value,
   * or the length is encoded as a 64-bit value.  
   */
  public final int
  readNestedTlvsStart(int expectedType) throws EncodingException
  {
    return readTypeAndLength(expectedType) + input_.position();
  }
  
  /**
   * Call this after reading all nested TLVs to skip any remaining unrecognized 
   * TLVs and to check if the input buffer position after the final nested TLV 
   * matches the endOffset returned by readNestedTlvsStart. Update the input 
   * buffer position as needed if skipping TLVs. 
   * @param endOffset The offset of the end of the parent TLV, returned 
   * by readNestedTlvsStart.
   * @throws EncodingException if the TLV length does not equal the total length 
   * of the nested TLVs.
   */
  public final void
  finishNestedTlvs(int endOffset) throws EncodingException
  {
    // We expect the position to be endOffset, so check this first.
    if (input_.position() == endOffset)
      return;

    // Skip remaining TLVs.
    while (input_.position() < endOffset) {
      // Skip the type VAR-NUMBER.
      readVarNumber();
      // Read the length and update the position.
      int length = readVarNumber();
      int newPosition = input_.position() + length;
      // Check newPosition before updating input_position since it would
      //   throw its own exception.
      if (newPosition > input_.remaining())
        throw new EncodingException("TLV length exceeds the buffer length");
      input_.position(newPosition);
    }

    if (input_.position() != endOffset)
      throw new EncodingException
        ("TLV length does not equal the total length of the nested TLVs");
  }
  
  /**
   * Decode the type from the input starting at the input buffer position, and 
   * if it is the expectedType, then return true, else false.  However, if the 
   * input buffer position is greater than or equal to endOffset, then return 
   * false and don't try to read the type. Do not update the input buffer 
   * position.
   * @param expectedType The expected type as a 32-bit Java int.
   * @param endOffset The offset of the end of the parent TLV, returned 
   * by readNestedTlvsStart.
   * @return true if the type of the next TLV is the expectedType, otherwise 
   * false.
   */
  public final boolean
  peekType(int expectedType, int endOffset) throws EncodingException
  {
    if (input_.position() >= endOffset)
      // No more sub TLVs to look at.
      return false;
    else {
      int savePosition = input_.position();
      int type = readVarNumber();
      // Restore the position.
      input_.position(savePosition);

      return type == expectedType;
    }
  }
  
  /**
   * Decode a non-negative integer in NDN-TLV and return it. Update the input
   * buffer position by length.
   * @param length The number of bytes in the encoded integer.
   * @return The integer as a Java 64-bit long.
   * @throws EncodingException if length is an invalid length for a TLV 
   * non-negative integer.
   */
  public final long
  readNonNegativeInteger(int length) throws EncodingException
  {
    if (length == 1)
      return (long)input_.get() & 0xff;
    else if (length == 2)
       return (((long)input_.get() & 0xff) << 8) +
               ((long)input_.get() & 0xff);
    else if (length == 4)
       return (((long)input_.get() & 0xff) << 24) +
              (((long)input_.get() & 0xff) << 16) +
              (((long)input_.get() & 0xff) << 8) +
               ((long)input_.get() & 0xff);
    else if (length == 8)
       return (((long)input_.get() & 0xff) << 56) +
              (((long)input_.get() & 0xff) << 48) +
              (((long)input_.get() & 0xff) << 40) +
              (((long)input_.get() & 0xff) << 32) +
              (((long)input_.get() & 0xff) << 24) +
              (((long)input_.get() & 0xff) << 16) +
              (((long)input_.get() & 0xff) << 8) +
               ((long)input_.get() & 0xff);
    else
      throw new EncodingException("Invalid length for a TLV nonNegativeInteger");
  }
  
  /**
   * Decode the type and length from the input starting at the input buffer
   * position, expecting the type to be expectedType. Then decode a non-negative 
   * integer in NDN-TLV and return it. Update the input buffer position.
   * @param expectedType The expected type as a 32-bit Java int.
   * @return The integer as a Java 64-bit long.
   * @throws EncodingException if did not get the expected TLV type or can't 
   * decode the value.
   */
  public final long
  readNonNegativeIntegerTlv(int expectedType) throws EncodingException
  {
    int length = readTypeAndLength(expectedType);
    return readNonNegativeInteger(length);
  }

  /**
   * Peek at the next TLV, and if it has the expectedType then call 
   * readNonNegativeIntegerTlv and return the integer.  Otherwise, return -1.  
   * However, if the input buffer position is greater than or equal to 
   * endOffset, then return -1 and don't try to read the type.
   * @param expectedType The expected type as a 32-bit Java int.
   * @param endOffset The offset of the end of the parent TLV, returned 
   * by readNestedTlvsStart.
   * @return The integer as a Java 64-bit long or -1 if the next TLV doesn't 
   * have the expected type.
   */
  public final long
  readOptionalNonNegativeIntegerTlv
    (int expectedType, int endOffset) throws EncodingException
  {
    if (peekType(expectedType, endOffset))
      return readNonNegativeIntegerTlv(expectedType);
    else
      return -1;
  }

  /**
   * Decode the type and length from the input starting at the input buffer
   * position, expecting the type to be expectedType. Then return a ByteBuffer
   * of the bytes in the value. Update the input buffer position.
   * @param expectedType The expected type as a 32-bit Java int.
   * @return The bytes in the value as a slice on the input buffer.  This is
   * not a copy of the bytes in the input buffer. If you need a copy, then you 
   * must make a copy of the return value.
   * @throws EncodingException if did not get the expected TLV type.
   */
  public final ByteBuffer
  readBlobTlv(int expectedType) throws EncodingException
  {
    int length = readTypeAndLength(expectedType);
    int saveLimit = input_.limit();
    input_.limit(input_.position() + length);
    ByteBuffer result = input_.slice();
    // Restore the limit.
    input_.limit(saveLimit);

    // readTypeAndLength already checked if length exceeds the input buffer.
    input_.position(input_.position() + length);
    return result;
  }
    
  /**
   * Peek at the next TLV, and if it has the expectedType then call readBlobTlv 
   * and return the value.  Otherwise, return null. However, if the input buffer 
   * position is greater than or equal to endOffset, then return null and don't 
   * try to read the type.
   * @param expectedType The expected type as a 32-bit Java int.
   * @param endOffset The offset of the end of the parent TLV, returned 
   * by readNestedTlvsStart.
   * @return The bytes in the value as a slice on the input buffer or null if 
   * the next TLV doesn't have the expected type. This is not a copy of the 
   * bytes in the input buffer. If you need a copy, then you must make a copy of 
   * the return value.
   */
  public final ByteBuffer
  readOptionalBlobTlv(int expectedType, int endOffset) throws EncodingException
  {
    if (peekType(expectedType, endOffset))
      return readBlobTlv(expectedType);
    else
      return null;
  }
  
  /**
   * Peek at the next TLV, and if it has the expectedType then read a type and 
   * value, ignoring the value, and return true. Otherwise, return false.
   * However, if the input buffer position is greater than or equal to 
   * endOffset, then return false and don't try to read the type and value.
   * @param expectedType The expected type as a 32-bit Java int.
   * @param endOffset The offset of the end of the parent TLV, returned 
   * by readNestedTlvsStart.
   * @return true, or else false if the next TLV doesn't have the 
   * expected type.
   */
  public final boolean
  readBooleanTlv(int expectedType, int endOffset) throws EncodingException
  {
    if (peekType(expectedType, endOffset)) {
      int length = readTypeAndLength(expectedType);
      // We expect the length to be 0, but update offset anyway.
      input_.position(input_.position() + length);
      return true;
    }
    else
      return false;
  }
  
  /**
   * Get the input buffer position (offset), used for the next read.
   * @return The input buffer position (offset).
   */
  public final int
  getOffset()
  {
    return input_.position();
  }
  
  /**
   * Set the offset into the input, used for the next read.
   * @param offset The new offset.
   */
  public final void
  seek(int offset)
  {
    input_.position(offset);
  }
  
  private final ByteBuffer input_;
}