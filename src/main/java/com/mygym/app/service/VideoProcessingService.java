package com.mygym.app.service;

import com.mygym.app.model.Keypoints;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class VideoProcessingService {

    private final String OUTPUT_DIR = System.getProperty("user.dir") + "/static/videos/";

    public VideoProcessingService() {
        new File(OUTPUT_DIR).mkdirs();
    }

    public List<BufferedImage> extractKeyframes(File videoFile, int frameInterval) throws Exception {
        List<BufferedImage> frameList = new ArrayList<>();
        
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();
            Frame frame;
            int counter = 0;

            while ((frame = grabber.grabImage()) != null) {
                counter++;
                if (counter % frameInterval == 0) {
                    BufferedImage rawCanvas = converter.convert(frame);
                    if (rawCanvas != null) {
                        frameList.add(cloneBufferedImage(rawCanvas)); 
                    }
                }
            }
            grabber.stop();
        }
        return frameList;
    }

    public String generateSkeletonOverlayVideo(File videoFile, PoseEstimationService poseService) throws Exception {
        String outputFileName = UUID.randomUUID().toString() + ".mp4";
        File outputFile = new File(OUTPUT_DIR + outputFileName);

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile)) {
            grabber.start();

            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outputFile, grabber.getImageWidth(), grabber.getImageHeight());
                 Java2DFrameConverter converter = new Java2DFrameConverter()) {
                
                recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264); 
                recorder.setFormat("mp4");
                recorder.setFrameRate(grabber.getFrameRate());
                recorder.start();

                Frame frame;
                while ((frame = grabber.grabImage()) != null) {
                    // Pull the raw native frame pointer data
                    BufferedImage rawCanvas = converter.convert(frame);
                    if (rawCanvas == null) continue;

                    // FIX: Mutate a single frame tracking buffer rather than cloning heap structures
                    Keypoints kp = poseService.predictKeypoints(rawCanvas);
                    
                    // Force clean unmanaged AWT rendering pointers via explicit try-finally blocks
                    Graphics2D g = rawCanvas.createGraphics();
                    try {
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        double scaleX = (double) grabber.getImageWidth() / 192.0;
                        double scaleY = (double) grabber.getImageHeight() / 192.0;

                        int sX = (int) (kp.shoulderX() * scaleX), sY = (int) (kp.shoulderY() * scaleY);
                        int hX = (int) (kp.hipX() * scaleX),      hY = (int) (kp.hipY() * scaleY);
                        int kX = (int) (kp.kneeX() * scaleX),      kY = (int) (kp.kneeY() * scaleY);
                        int aX = (int) (kp.ankleX() * scaleX),    aY = (int) (kp.ankleY() * scaleY);

                        g.setColor(new Color(57, 255, 20, 80)); 
                        g.setStroke(new BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.drawLine(sX, sY, hX, hY); g.drawLine(hX, hY, kX, kY); g.drawLine(kX, kY, aX, aY);

                        g.setColor(new Color(57, 255, 20, 255)); 
                        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.drawLine(sX, sY, hX, hY); g.drawLine(hX, hY, kX, kY); g.drawLine(kX, kY, aX, aY);

                        g.setColor(Color.WHITE);
                        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.drawLine(sX, sY, hX, hY); g.drawLine(hX, hY, kX, kY); g.drawLine(kX, kY, aX, aY);

                        g.setColor(new Color(255, 0, 50)); 
                        g.fillOval(sX - 8, sY - 8, 16, 16); g.fillOval(hX - 8, hY - 8, 16, 16);
                        g.fillOval(kX - 8, kY - 8, 16, 16); g.fillOval(aX - 8, aY - 8, 16, 16);
                    } finally {
                        // FIX: Forces the unmanaged native OS graphics handles to release back to Windows memory pools immediately
                        g.dispose();
                    }

                    recorder.record(converter.convert(rawCanvas));
                }
                recorder.stop();
            }
            grabber.stop();
        }
        
        System.gc();
        return "/videos/" + outputFileName; 
    }


    private BufferedImage drawSkeletonOnFrame(BufferedImage img, Keypoints kp, int originalW, int originalH) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        double scaleX = (double) originalW / 192.0;
        double scaleY = (double) originalH / 192.0;

        int sX = (int) (kp.shoulderX() * scaleX), sY = (int) (kp.shoulderY() * scaleY);
        int hX = (int) (kp.hipX() * scaleX),      hY = (int) (kp.hipY() * scaleY);
        int kX = (int) (kp.kneeX() * scaleX),      kY = (int) (kp.kneeY() * scaleY);
        int aX = (int) (kp.ankleX() * scaleX),    aY = (int) (kp.ankleY() * scaleY);

        g.setColor(new Color(57, 255, 20, 80)); 
        g.setStroke(new BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(sX, sY, hX, hY); 
        g.drawLine(hX, hY, kX, kY); 
        g.drawLine(kX, kY, aX, aY); 

        g.setColor(new Color(57, 255, 20, 255)); 
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(sX, sY, hX, hY); 
        g.drawLine(hX, hY, kX, kY); 
        g.drawLine(kX, kY, aX, aY); 

        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(sX, sY, hX, hY); 
        g.drawLine(hX, hY, kX, kY); 
        g.drawLine(kX, kY, aX, aY); 

        g.setColor(new Color(255, 0, 50)); 
        g.fillOval(sX - 8, sY - 8, 16, 16);
        g.fillOval(hX - 8, hY - 8, 16, 16);
        g.fillOval(kX - 8, kY - 8, 16, 16);
        g.fillOval(aX - 8, aY - 8, 16, 16);

        g.dispose();
        return img;
    }

    private BufferedImage cloneBufferedImage(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
        dst.setRGB(0, 0, w, h, pixels, 0, w);
        return dst;
    }
}
