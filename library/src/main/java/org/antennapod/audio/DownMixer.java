package org.antennapod.audio;

class DownMixer {

    static void downMix(byte[] modifiedSamples) {
        for (int i = 0; (i + 3) < modifiedSamples.length; i += 4) {
            short left = (short) ((modifiedSamples[i] & 0xff) | (modifiedSamples[i + 1] << 8));
            short right = (short) ((modifiedSamples[i + 2] & 0xff) | (modifiedSamples[i + 3] << 8));
            short value = (short) (0.5 * left + 0.5 * right);

            modifiedSamples[i] = (byte) (value & 0xff);
            modifiedSamples[i + 1] = (byte) (value >> 8);
            modifiedSamples[i + 2] = (byte) (value & 0xff);
            modifiedSamples[i + 3] = (byte) (value >> 8);
        }
    }

}
