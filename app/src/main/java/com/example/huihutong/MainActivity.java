package com.example.huihutong;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity {
    private ImageView qrcodeImageView;
    private TextView tipsView;

    private SharedPreferences.Editor editor;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dialog alertDialog;
    
    private String satoken = "";
    private String openId = "";
    private final int REQUEST_INTERVAL = 8000; // 请求间隔时间（毫秒）
    private float scaleFactor = 1.0f;
    private boolean inScaling = false;

    private void setBrightnessMax(Activity activity){
        WindowManager.LayoutParams systemLayoutAttr = activity.getWindow().getAttributes();

        systemLayoutAttr.screenBrightness = 1;

    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setBrightnessMax(this);

        final SharedPreferences sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE);
        editor = sharedPreferences.edit();

        satoken = sharedPreferences.getString("satoken", "");
        openId = sharedPreferences.getString("openId", "");
        scaleFactor = sharedPreferences.getFloat("scale", 1.0f);

        qrcodeImageView = findViewById(R.id.qrcode_image);
        tipsView = findViewById(R.id.tips);

        final int imgSize = getResources().getDisplayMetrics().widthPixels;
        qrcodeImageView.getLayoutParams().width = imgSize;
        qrcodeImageView.getLayoutParams().height = imgSize;
        qrcodeImageView.requestLayout();
        qrcodeImageView.setScaleX(scaleFactor);
        qrcodeImageView.setScaleY(scaleFactor);

        final Button setOpenIdBtn = findViewById(R.id.set_openid);
        setOpenIdBtn.setOnClickListener(v -> inputOpenId("修改OpenId"));
        qrcodeImageView.setOnClickListener(v -> refreshQRCode());

        final ScaleGestureDetector scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.OnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                scaleFactor = Math.max(0.4f, Math.min(scaleFactor, 1.0f)); // 控制缩放范围
                qrcodeImageView.setScaleX(scaleFactor);
                qrcodeImageView.setScaleY(scaleFactor);
                return false;
            }

            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                editor.putFloat("scale", scaleFactor).apply();
            }
        });

        qrcodeImageView.setOnTouchListener((v,event)->{
            scaleGestureDetector.onTouchEvent(event);
            boolean disableClick = scaleGestureDetector.isInProgress() || inScaling;
            inScaling = scaleGestureDetector.isInProgress() || (inScaling && event.getAction()!=MotionEvent.ACTION_UP);
            return disableClick;
        });
    }

    private void inputOpenId(String msg) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置OpenId");
        builder.setMessage(msg);
        final EditText input = new EditText(this);
        input.setText(openId);
        builder.setView(input);
        builder.setPositiveButton("确定", (dialog, which) -> {
            openId = input.getText().toString();
            editor.putString("openId", openId);
            satoken = "";
            editor.putString("satoken", satoken).apply();
            refreshQRCode();
        });
        alertDialog = builder.create();
        alertDialog.show();
    }

    private String request(String path) throws IOException {
        final URL urlObj = new URL("https://api.215123.cn"+path);
        final HttpURLConnection connection = (HttpURLConnection) urlObj.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("satoken", satoken);
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("responseCode=" + connection.getResponseCode());
        }
        String responseText;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            responseText = reader.lines().collect(Collectors.joining());
        } catch (IOException e) {
            throw new IOException("Try-With-Resources Fail");
        }
        return responseText;
    }

    @SuppressLint("SetTextI18n")
    private Integer getSatoken() {
        try {
            final String responseText = request("/web-app/auth/certificateLogin?openId="+openId);
            try {
                // 读取data下面的token
                satoken = new JSONObject(responseText).getJSONObject("data").getString("token");
                if (satoken.equals("")||satoken.equals("null")) {
                    throw new JSONException("satoken为空");
                }
                editor.putString("satoken", satoken).apply();
                return 0;
            } catch (JSONException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "satoken解析失败", Toast.LENGTH_SHORT).show();
                    inputOpenId("OpenId无效");
                });
                return -1;
            }
        } catch (IOException e) {
            e.printStackTrace();
            runOnUiThread(() -> {
                tipsView.setText("SATOKEN更新失败！");
                Toast.makeText(MainActivity.this, "网络错误", Toast.LENGTH_SHORT).show();
            });
            return REQUEST_INTERVAL;
        }
    }

    private void refreshQRCode() {
        handler.removeCallbacksAndMessages(null);
        new Thread(() -> {
            try {
                runOnUiThread(() -> tipsView.setText("正在更新二维码..."));
                final String responseText = request("/pms/welcome/make-qrcode");
                try {
                    final String code = new JSONObject(responseText).getString("data");
                    if (code.equals("")||code.equals("null")) {
                        throw new JSONException("code为空");
                    }
                    handler.postDelayed(this::refreshQRCode, REQUEST_INTERVAL);
                    runOnUiThread(() -> generateQRCode(code));
                } catch (JSONException e) {
                    e.printStackTrace();
                    final Integer retryTime = getSatoken();
                    if (retryTime>=0) {
                        handler.postDelayed(this::refreshQRCode, retryTime);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tipsView.setText("二维码更新失败！");
                    Toast.makeText(MainActivity.this, "网络错误", Toast.LENGTH_SHORT).show();
                });
                handler.postDelayed(this::refreshQRCode, REQUEST_INTERVAL);
            }
        }).start();
    }

    private void generateQRCode(String data) {
        tipsView.setText("二维码更新成功！");

        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 1000, 1000);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            qrcodeImageView.setImageBitmap(bitmap);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
        if (alertDialog != null) {
            alertDialog.dismiss();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshQRCode();
    }

}