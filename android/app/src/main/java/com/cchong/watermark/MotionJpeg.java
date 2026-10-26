package com.cchong.watermark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MotionJpeg {
    static final class Parts {
        final byte[] still, gainmap, video;
        final String cameraPrefix;
        final long timestampUs;
        Parts(byte[] still, byte[] gainmap, byte[] video, String cameraPrefix, long timestampUs) {
            this.still = still; this.gainmap = gainmap; this.video = video;
            this.cameraPrefix = cameraPrefix; this.timestampUs = timestampUs;
        }
    }

    static Parts split(byte[] data) {
        int end = jpegEnd(data);
        if (end < 0 || end >= data.length) return null;
        String header = new String(data, 0, end, StandardCharsets.ISO_8859_1);
        int videoStart = -1;
        Matcher lengths = Pattern.compile("(?:Item:Length|GContainer:ItemLength)=[\"'](\\d+)").matcher(header);
        while (lengths.find()) {
            try {
                long length = Long.parseLong(lengths.group(1));
                if (length > 0 && length <= data.length - end && isMp4(data, (int) (data.length - length)))
                    videoStart = (int) (data.length - length);
            } catch (NumberFormatException ignored) { }
        }
        if (videoStart < 0) {
            for (int i = end; i + 8 <= data.length; i++) {
                if (isMp4(data, i)) { videoStart = i; break; }
            }
        }
        if (videoStart < 0) return null;
        byte[] gainmap = header.contains("Item:Semantic=\"GainMap\"") ? Arrays.copyOfRange(data, end, videoStart) : new byte[0];
        String prefix = header.contains("GCamera:MotionPhoto") ? "GCamera" : "Camera";
        Matcher time = Pattern.compile("MotionPhotoPresentationTimestampUs=[\"'](-?\\d+)").matcher(header);
        long timestamp = -1;
        if (time.find()) try { timestamp = Long.parseLong(time.group(1)); } catch (NumberFormatException ignored) { }
        return new Parts(Arrays.copyOfRange(data, 0, end), gainmap, Arrays.copyOfRange(data, videoStart, data.length), prefix, timestamp);
    }

    static boolean looksLikeMotion(byte[] data, String name) {
        if (name.toUpperCase(java.util.Locale.ROOT).startsWith("MVIMG_")) return true;
        byte[] key = "MotionPhoto".getBytes(StandardCharsets.US_ASCII);
        outer: for (int i = 0; i <= Math.min(data.length, 524288) - key.length; i++) {
            for (int j = 0; j < key.length; j++) if (data[i + j] != key[j]) continue outer;
            return true;
        }
        return false;
    }

    static byte[] join(byte[] encodedStill, Parts original, byte[] processedVideo) throws IOException {
        String prefix = original.cameraPrefix;
        String gain = original.gainmap.length == 0 ? "" :
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"GainMap\" Item:Length=\"" + original.gainmap.length + "\"/></rdf:li>";
        String xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:" + prefix + "=\"http://ns.google.com/photos/1.0/camera/\" " + prefix + ":MotionPhoto=\"1\" " + prefix + ":MotionPhotoVersion=\"1\" " + prefix + ":MotionPhotoPresentationTimestampUs=\"" + original.timestampUs + "\"/>" +
            "<rdf:Description rdf:about=\"\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"><Container:Directory><rdf:Seq>" +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\"/></rdf:li>" + gain +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"" + processedVideo.length + "\"/></rdf:li>" +
            "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>";
        byte[] payload = ("http://ns.adobe.com/xap/1.0/\0" + xmp).getBytes(StandardCharsets.UTF_8);
        if (payload.length + 2 > 65535) throw new IOException("动态照片元数据过大");
        ByteArrayOutputStream out = new ByteArrayOutputStream(encodedStill.length + original.gainmap.length + processedVideo.length + payload.length + 4);
        out.write(encodedStill, 0, 2);
        out.write(0xff); out.write(0xe1);
        int segmentLength = payload.length + 2;
        out.write(segmentLength >> 8); out.write(segmentLength & 255);
        out.write(payload);
        out.write(encodedStill, 2, encodedStill.length - 2);
        out.write(original.gainmap);
        out.write(processedVideo);
        return out.toByteArray();
    }

    static int jpegEnd(byte[] data) {
        if (data.length < 4 || (data[0] & 255) != 0xff || (data[1] & 255) != 0xd8) return -1;
        int position = 2;
        while (position + 3 < data.length) {
            if ((data[position] & 255) != 0xff) return -1;
            int marker = data[position + 1] & 255;
            if (marker == 0xda) {
                for (int i = position + 2; i + 1 < data.length; i++)
                    if ((data[i] & 255) == 0xff && (data[i + 1] & 255) == 0xd9) return i + 2;
                return -1;
            }
            if (marker == 0xd9) return position + 2;
            if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd7) { position += 2; continue; }
            int length = ((data[position + 2] & 255) << 8) | (data[position + 3] & 255);
            if (length < 2) return -1;
            position += length + 2;
        }
        return -1;
    }

    private static boolean isMp4(byte[] data, int offset) {
        if (offset < 0 || offset + 8 > data.length) return false;
        int size = ((data[offset] & 255) << 24) | ((data[offset + 1] & 255) << 16) | ((data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
        return size >= 8 && size <= data.length - offset && data[offset + 4] == 'f' && data[offset + 5] == 't' && data[offset + 6] == 'y' && data[offset + 7] == 'p';
    }
}
