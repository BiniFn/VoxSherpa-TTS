package com.CodeBySonu.VoxSherpa;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes raw PCM to AAC and muxes it into an M4B (MPEG-4 audio) container.
 *
 * M4B shares its container with M4A; the audiobook flavour carries chapter
 * metadata. Input PCM must be 16-bit signed mono at the rate we declare.
 *
 * Pipeline: MediaCodec (PCM -> AAC) -> MediaMuxer (.m4b)
 */
public class M4bExporter {

    public static final int AAC_BITRATE = 64000;   // 64 kbps: clean mono speech
    public static final int AAC_CHANNELS = 1;

    /** One chapter marker, in samples from the start of the book. */
    public static class ChapterMark {
        public final String title;
        public final long startSample;
        public ChapterMark(String title, long startSample) {
            this.title = title;
            this.startSample = startSample;
        }
    }

    public interface ProgressListener {
        void onProgress(int percent);   // 0..100
    }

    /**
     * @return absolute path of the written .m4b, or "" on failure
     */
    public static String export(Context ctx, byte[] pcm, int sampleRate,
                                String baseName, List<ChapterMark> chapters,
                                ProgressListener listener) {
        if (ctx == null || pcm == null || pcm.length < 2 || sampleRate <= 0) return "";

        File outDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MUSIC), "VoxEngine");
        if (!outDir.exists() && !outDir.mkdirs()) return "";

        String safe = baseName == null ? "audiobook" : baseName.replaceAll("[^a-zA-Z0-9 _-]", "");
        if (safe.trim().isEmpty()) safe = "audiobook";
        File outFile = new File(outDir, safe + ".m4b");
        if (outFile.exists() && !outFile.delete()) return "";

        MediaCodec codec = null;
        MediaMuxer muxer = null;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int trackIndex = -1;
        boolean muxerStarted = false;
        boolean inputDone = false;
        boolean outputDone = false;

        try {
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, AAC_CHANNELS);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 128 * 1024);

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            muxer = new MediaMuxer(outFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            final int bytesPerSample = 2;               // 16-bit mono
            final long usPerSample = 1_000_000L / sampleRate;
            final int chunkBytes = 8192;                // 4096 samples per push
            int offset = 0;
            long presentationUs = 0;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        if (inBuf != null) {
                            inBuf.clear();
                            int remaining = pcm.length - offset;
                            if (remaining <= 0) {
                                // all PCM fed — signal end of stream with the running timestamp
                                codec.queueInputBuffer(inIndex, 0, 0, presentationUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                int toCopy = Math.min(remaining, chunkBytes);
                                inBuf.put(pcm, offset, toCopy);
                                codec.queueInputBuffer(inIndex, 0, toCopy, presentationUs, 0);
                                offset += toCopy;
                                // advance the clock by the samples just queued
                                presentationUs += (toCopy / bytesPerSample) * usPerSample;
                                if (listener != null) {
                                    listener.onProgress(
                                            Math.min(99, (int) (100L * offset / pcm.length)));
                                }
                            }
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;   // loop round; input side gets another chance
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    }
                } else if (outIndex >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (outBuf != null && info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        muxer.writeSampleData(trackIndex, outBuf, info);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if (eos) outputDone = true;
                }
            }

        } catch (Exception e) {
            return "";
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) {}
            try {
                if (muxer != null) {
                    if (muxerStarted) muxer.stop();
                    muxer.release();
                }
            } catch (Throwable ignored) {}
        }

        // A container with no audio track is not a usable file
        if (!muxerStarted || !outFile.exists() || outFile.length() < 128) {
            try { if (outFile.exists()) outFile.delete(); } catch (Throwable ignored) {}
            return "";
        }
        if (listener != null) listener.onProgress(100);
        return outFile.getAbsolutePath();
    }

    /**
     * Builds chapter marks from text chunks. Sample positions are estimated from
     * character count at an approximate spoken rate.
     */
    public static List<ChapterMark> marksFromTexts(List<String> chapterTexts, List<String> titles,
                                                    int sampleRate) {
        List<ChapterMark> out = new ArrayList<>();
        if (chapterTexts == null || chapterTexts.isEmpty()) return out;
        int rate = sampleRate > 0 ? sampleRate : 22050;
        long cursor = 0;
        for (int i = 0; i < chapterTexts.size(); i++) {
            String t = chapterTexts.get(i);
            String name = (titles != null && i < titles.size() && titles.get(i) != null)
                    ? titles.get(i) : ("Chapter " + (i + 1));
            out.add(new ChapterMark(name, cursor));
            int chars = t == null ? 0 : t.length();
            cursor += (long) (chars / 14.0 * rate);   // ~14 chars/sec spoken
        }
        return out;
    }
}
