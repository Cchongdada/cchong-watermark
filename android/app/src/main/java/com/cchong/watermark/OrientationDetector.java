package com.cchong.watermark;

import android.content.Context;
import android.graphics.Bitmap;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.util.Collections;

/** Offline natural-image orientation model; uncertain predictions never rotate. */
final class OrientationDetector {
    static final class Result {
        final int rotation; final boolean confident;
        Result(int rotation, boolean confident) { this.rotation=rotation; this.confident=confident; }
    }
    private static OrtSession model;
    private static OrtEnvironment environment;
    private static synchronized OrtSession session(Context context) throws Exception {
        if(model!=null)return model;
        File file=new File(context.getNoBackupFilesDir(),"orientation-cffe911c.onnx");
        if(!file.isFile() || file.length()!=80578424L) {
            File temporary=new File(context.getNoBackupFilesDir(),"orientation.tmp");
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            try(InputStream in=context.getAssets().open("orientation.onnx"); OutputStream out=new FileOutputStream(temporary)) {
                byte[] buffer=new byte[65536];int count;
                while((count=in.read(buffer))!=-1){digest.update(buffer,0,count);out.write(buffer,0,count);}
            }
            StringBuilder hash=new StringBuilder();for(byte value:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            if(!hash.toString().equals("cffe911c1dff47fbfbbd90110aaab9c07134645c460d35b3ae8832079bea91ba")){temporary.delete();throw new IOException("方向模型校验失败");}
            if(file.exists())file.delete();
            if(!temporary.renameTo(file))throw new IOException("无法准备离线方向模型");
        }
        environment=OrtEnvironment.getEnvironment();
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            model=environment.createSession(file.getAbsolutePath(),options);
        }
        return model;
    }
    static synchronized Result detect(Context context, Bitmap photo) throws Exception {
        OrtSession network=session(context);
        String name=network.getInputNames().iterator().next();
        long[] shape=((TensorInfo)network.getInputInfo().get(name).getInfo()).getShape();
        int side=(int)shape[3], resized=side+32;
        Bitmap scaled=Bitmap.createScaledBitmap(photo,resized,resized,true);
        int[] pixels=new int[side*side];scaled.getPixels(pixels,0,side,16,16,side,side);
        if(scaled!=photo)scaled.recycle();
        float[] values=new float[side*side*3];float[] mean={.485f,.456f,.406f},std={.229f,.224f,.225f};
        for(int channel=0;channel<3;channel++)for(int i=0;i<pixels.length;i++) {
            int component=(pixels[i]>>(16-channel*8))&255;
            values[channel*pixels.length+i]=(component/255f-mean[channel])/std[channel];
        }
        try(OnnxTensor input=OnnxTensor.createTensor(environment,FloatBuffer.wrap(values),new long[]{1,3,side,side});
            OrtSession.Result output=network.run(Collections.singletonMap(name,input))) {
            float[] logits=((float[][])output.get(0).getValue())[0];
            int best=0;for(int i=1;i<4;i++)if(logits[i]>logits[best])best=i;
            double sum=0;for(float logit:logits)sum+=Math.exp(logit-logits[best]);
            return new Result(best*90,1.0/sum>=.90);
        }
    }
}
