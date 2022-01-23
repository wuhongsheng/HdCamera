package com.hd.hdcamera.rtmp.simplertmp.packets;


import com.hd.hdcamera.rtmp.simplertmp.io.ChunkStreamInfo;

/**
 * Audio data packet
 *  
 * @author francois
 */
public class Audio extends ContentData {

    public Audio(RtmpHeader header) {
        super(header);
    }

    public Audio() {
        super(new RtmpHeader(RtmpHeader.ChunkType.TYPE_0_FULL, ChunkStreamInfo.RTMP_CID_AUDIO, RtmpHeader.MessageType.AUDIO));
    }

    @Override
    public String toString() {
        return "RTMP Audio";
    }
}
