package com.goodecho.app;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

public class OpusFileWriter implements Closeable {

    private static final int[] VALID_FRAME_SIZES = {120, 240, 480, 960, 1920, 2880};

    private final FileOutputStream fileOut;
    private final OpusEncoder encoder;
    private final int channels;
    private final int sampleRate;
    private final int frameSize;
    private final int frameBytes;

    private final byte[] pcmBuf;
    private int pcmBufBytes;
    private long totalValidSamples;
    private int pageSeq;
    private boolean headersWritten;

    private byte[] lastPacket;
    private int lastPacketLen;
    private long lastPacketSamples;
    private boolean hasLastPacket;

    public OpusFileWriter(File file, int sampleRate) throws IOException {
        this(file, sampleRate, 1);
    }

    public OpusFileWriter(File file, int sampleRate, int channels) throws IOException {
        this.fileOut = new FileOutputStream(file);
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frameSize = chooseFrameSize(sampleRate);
        this.frameBytes = frameSize * 2 * channels;

        try {
            this.encoder = new OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_AUDIO);
        } catch (OpusException e) {
            fileOut.close();
            throw new IOException("Failed to create OpusEncoder", e);
        }

        this.pcmBuf = new byte[frameBytes];
        this.pcmBufBytes = 0;
        this.totalValidSamples = 0;
        this.pageSeq = 0;
        this.headersWritten = false;
        this.lastPacket = null;
        this.lastPacketLen = 0;
        this.lastPacketSamples = 0;
        this.hasLastPacket = false;

        writeHeaders();
    }

    private static int chooseFrameSize(int sampleRate) {
        int maxSamples = sampleRate * 60 / 1000;
        int target = sampleRate * 20 / 1000;
        int best = 480;
        for (int v : VALID_FRAME_SIZES) {
            if (v > maxSamples) break;
            if (Math.abs(v - target) < Math.abs(best - target)) {
                best = v;
            }
        }
        return best;
    }

    private void writeHeaders() throws IOException {
        byte[] head = buildOpusHead();
        writeOggPage(head, head.length, 0, true, false);

        byte[] tags = buildOpusTags();
        writeOggPage(tags, tags.length, 0, false, false);

        headersWritten = true;
    }

    private byte[] buildOpusHead() {
        byte[] buf = new byte[19];
        System.arraycopy("OpusHead".getBytes(), 0, buf, 0, 8);
        buf[8] = 1;
        buf[9] = (byte) channels;
        writeLE16(buf, 10, 312);
        writeLE32(buf, 12, sampleRate);
        writeLE16(buf, 16, 0);
        buf[18] = 0;
        return buf;
    }

    private byte[] buildOpusTags() throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        baos.write("OpusTags".getBytes());
        byte[] vendor = "Echo".getBytes("UTF-8");
        writeLE32(baos, vendor.length);
        baos.write(vendor);
        writeLE32(baos, 0);
        return baos.toByteArray();
    }

    public void write(byte[] pcm, int offset, int count) throws IOException {
        while (count > 0) {
            int avail = frameBytes - pcmBufBytes;
            int copy = Math.min(count, avail);
            System.arraycopy(pcm, offset, pcmBuf, pcmBufBytes, copy);
            pcmBufBytes += copy;
            offset += copy;
            count -= copy;

            if (pcmBufBytes == frameBytes) {
                totalValidSamples += frameSize;
                encodeAndCommit(frameSize, false);
            }
        }
    }

    public void writeFrame(byte[] frame, int offset, int length) throws IOException {
        byte[] data = new byte[length];
        System.arraycopy(frame, offset, data, 0, length);
        totalValidSamples += OpusRingBuffer.FRAME_SIZE;
        commitFrame(data, length, totalValidSamples, false);
    }

    private void commitFrame(byte[] data, int dataLen, long granuleSamples, boolean isLast) throws IOException {
        if (isLast) {
            if (hasLastPacket) {
                byte[] prevPage = buildOggPage(lastPacket, lastPacketLen, samplesToGranule(lastPacketSamples), pageSeq++, false, false);
                fileOut.write(prevPage);
            }
            byte[] finalPage = buildOggPage(data, dataLen, samplesToGranule(granuleSamples), pageSeq++, false, true);
            fileOut.write(finalPage);
            hasLastPacket = false;
        } else {
            if (hasLastPacket) {
                byte[] prevPage = buildOggPage(lastPacket, lastPacketLen, samplesToGranule(lastPacketSamples), pageSeq++, false, false);
                fileOut.write(prevPage);
            }
            lastPacket = data;
            lastPacketLen = dataLen;
            lastPacketSamples = granuleSamples;
            hasLastPacket = true;
        }
    }

    private void encodeAndCommit(int validSamples, boolean isLast) throws IOException {
        byte[] packet = new byte[4096];
        int encoded;
        try {
            encoded = encoder.encode(pcmBuf, 0, frameSize, packet, 0, packet.length);
        } catch (OpusException e) {
            throw new IOException("Opus encode failed", e);
        }
        pcmBufBytes = 0;

        byte[] data = new byte[encoded];
        System.arraycopy(packet, 0, data, 0, encoded);

        commitFrame(data, encoded, isLast ? validSamples : totalValidSamples, isLast);
    }

    private long samplesToGranule(long samples) {
        return samples * 48000L / sampleRate + 312;
    }

    @Override
    public void close() throws IOException {
        if (pcmBufBytes > 0 && headersWritten) {
            long prevValid = totalValidSamples;
            totalValidSamples += pcmBufBytes / (2 * channels);
            encodeAndCommit((int) (totalValidSamples - prevValid), true);
        } else if (hasLastPacket) {
            byte[] finalPage = buildOggPage(lastPacket, lastPacketLen, samplesToGranule(lastPacketSamples), pageSeq++, false, true);
            fileOut.write(finalPage);
            hasLastPacket = false;
        }

        fileOut.close();
    }

    public long getTotalPcmSamples() {
        return totalValidSamples;
    }

    private void writeOggPage(byte[] data, int dataLen, long granule, boolean bos, boolean eos) throws IOException {
        byte[] page = buildOggPage(data, dataLen, granule, pageSeq++, bos, eos);
        fileOut.write(page);
    }

    private byte[] buildOggPage(byte[] data, int dataLen, long granule, int seq, boolean bos, boolean eos) {
        int segCount = (dataLen + 254) / 255;
        int headerLen = 27 + segCount;
        int pageLen = headerLen + dataLen;
        byte[] page = new byte[pageLen];

        page[0] = 'O'; page[1] = 'g'; page[2] = 'g'; page[3] = 'S';
        page[4] = 0;
        page[5] = (byte) ((bos ? 1 : 0) | (eos ? 2 : 0));
        writeLE64(page, 6, granule);
        writeLE32(page, 14, 1);
        writeLE32(page, 18, seq);
        writeLE32(page, 22, 0);
        page[26] = (byte) segCount;

        for (int i = 0; i < segCount; i++) {
            int start = i * 255;
            int remaining = dataLen - start;
            page[27 + i] = (byte) Math.min(remaining, 255);
        }

        System.arraycopy(data, 0, page, 27 + segCount, dataLen);

        CRC32 crc = new CRC32();
        crc.update(page);
        int crcVal = (int) crc.getValue();
        page[22] = (byte) (crcVal & 0xFF);
        page[23] = (byte) ((crcVal >> 8) & 0xFF);
        page[24] = (byte) ((crcVal >> 16) & 0xFF);
        page[25] = (byte) ((crcVal >> 24) & 0xFF);

        return page;
    }

    private static void writeLE16(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeLE32(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeLE64(byte[] buf, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte) ((value >> (i * 8)) & 0xFF);
        }
    }

    private static void writeLE32(java.io.ByteArrayOutputStream baos, int value) {
        baos.write(value & 0xFF);
        baos.write((value >> 8) & 0xFF);
        baos.write((value >> 16) & 0xFF);
        baos.write((value >> 24) & 0xFF);
    }

    private static void writeLE16(java.io.ByteArrayOutputStream baos, int value) {
        baos.write(value & 0xFF);
        baos.write((value >> 8) & 0xFF);
    }
}
