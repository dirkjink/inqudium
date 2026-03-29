package eu.inqudium.core.callid;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A highly optimized, thread-safe utility class for generating 96-bit identifiers.
 * <p>
 * This implementation generates 96 bits of randomness (12 bytes) and formats
 * them as a 24-character hexadecimal string without creating intermediate objects.
 */
public class Fast96BitId {

  // Base64URL encoding alphabet (URL and filename safe, RFC 4648)
  private static final char[] BASE64_URL_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

  // Pre-calculated lookup table for extremely fast hex conversion
  private static final char[] HEX_DIGITS =
      {'0', '1', '2', '3', '4', '5', '6', '7',
          '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

  private Fast96BitId() {
    // Prevent instantiation
  }

  /**
   * Generates a 96-bit random identifier formatted as a 24-character hex string.
   *
   * @return A 24-character hexadecimal string.
   */
  public static String randomIdHex() {
    ThreadLocalRandom random = ThreadLocalRandom.current();

    // Fetch exactly 96 bits of randomness
    long part1 = random.nextLong(); // 64 bits
    int part2 = random.nextInt();   // 32 bits

    // Initialize a character array of exactly 24 characters (96 bits / 4 bits per hex char)
    char[] idChars = new char[24];

    // Format the 64-bit part into the first 16 characters
    formatHexLong(part1, 16, idChars, 0);

    // Format the 32-bit part into the remaining 8 characters
    formatHexInt(part2, 8, idChars, 16);

    // Create the final String using a single allocation
    return new String(idChars);
  }

  /**
   * Generates a 96-bit random identifier formatted as a 16-character Base64URL string.
   *
   * @return A 16-character Base64URL string.
   */
  public static String randomIdBase64() {
    ThreadLocalRandom random = ThreadLocalRandom.current();

    // Fetch exactly 96 bits of randomness
    long p1 = random.nextLong(); // 64 bits
    int p2 = random.nextInt();   // 32 bits

    // Initialize a character array of exactly 16 characters (96 bits / 6 bits per char)
    char[] idChars = new char[16];

    // Extract the first 10 characters from the top 60 bits of p1
    idChars[0] = BASE64_URL_ALPHABET[(int) ((p1 >>> 58) & 63)];
    idChars[1] = BASE64_URL_ALPHABET[(int) ((p1 >>> 52) & 63)];
    idChars[2] = BASE64_URL_ALPHABET[(int) ((p1 >>> 46) & 63)];
    idChars[3] = BASE64_URL_ALPHABET[(int) ((p1 >>> 40) & 63)];
    idChars[4] = BASE64_URL_ALPHABET[(int) ((p1 >>> 34) & 63)];
    idChars[5] = BASE64_URL_ALPHABET[(int) ((p1 >>> 28) & 63)];
    idChars[6] = BASE64_URL_ALPHABET[(int) ((p1 >>> 22) & 63)];
    idChars[7] = BASE64_URL_ALPHABET[(int) ((p1 >>> 16) & 63)];
    idChars[8] = BASE64_URL_ALPHABET[(int) ((p1 >>> 10) & 63)];
    idChars[9] = BASE64_URL_ALPHABET[(int) ((p1 >>> 4) & 63)];

    // The 11th character straddles the boundary: bottom 4 bits of p1 and top 2 bits of p2
    idChars[10] = BASE64_URL_ALPHABET[(int) (((p1 & 0xF) << 2) | ((p2 >>> 30) & 3))];

    // Extract the remaining 5 characters from the lower 30 bits of p2
    idChars[11] = BASE64_URL_ALPHABET[(p2 >>> 24) & 63];
    idChars[12] = BASE64_URL_ALPHABET[(p2 >>> 18) & 63];
    idChars[13] = BASE64_URL_ALPHABET[(p2 >>> 12) & 63];
    idChars[14] = BASE64_URL_ALPHABET[(p2 >>> 6) & 63];
    idChars[15] = BASE64_URL_ALPHABET[p2 & 63];

    // Create the final String using a single allocation
    return new String(idChars);
  }

  /**
   * Efficiently converts bits of a long into hexadecimal characters.
   */
  private static void formatHexLong(long value, int hexCharacters, char[] buffer, int offset) {
    for (int i = 0; i < hexCharacters; i++) {
      // Calculate the right-shift distance (4 bits per hex character)
      int shift = (hexCharacters - 1 - i) * 4;
      // Shift the bits, mask out everything but the lowest 4 bits, and look up the char
      buffer[offset + i] = HEX_DIGITS[(int) ((value >>> shift) & 0x0F)];
    }
  }

  /**
   * Efficiently converts bits of an int into hexadecimal characters.
   */
  private static void formatHexInt(int value, int hexCharacters, char[] buffer, int offset) {
    for (int i = 0; i < hexCharacters; i++) {
      // Calculate the right-shift distance (4 bits per hex character)
      int shift = (hexCharacters - 1 - i) * 4;
      // Shift the bits, mask out everything but the lowest 4 bits, and look up the char
      buffer[offset + i] = HEX_DIGITS[(value >>> shift) & 0x0F];
    }
  }
}
