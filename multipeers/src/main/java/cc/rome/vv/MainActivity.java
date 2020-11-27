package cc.rome.vv;

import android.Manifest;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import cc.rome.vv.webrtc.WebRtcManager;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends AppCompatActivity implements EasyPermissions.PermissionCallbacks {

    private SurfaceViewRenderer localView;
    private WebRtcManager webRtcManager;

    private static final String[] CAMERA_AND_AUDIO = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    private static final int RC_PERMS = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        if (!hasPermissions()) {
            requestPermissions();
        } else {
            initWebRtc();
        }
    }

    @AfterPermissionGranted(RC_PERMS)
    private void initWebRtc() {
        webRtcManager = new WebRtcManager();
        webRtcManager.init(this.getApplicationContext());

        localView = findViewById(R.id.localView);
        webRtcManager.initLocalStream(localView);

        SurfaceViewRenderer[] remoteViews = new SurfaceViewRenderer[]{
                findViewById(R.id.remoteView),
                findViewById(R.id.remoteView2),
                findViewById(R.id.remoteView3)
        };

        webRtcManager.initRemoteViews(remoteViews);

        Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE);//打开webrtc的log输出

    }

    private void requestPermissions() {
        EasyPermissions.requestPermissions(
                this,
                "使用前需要您授权开启摄像头和麦克风",
                RC_PERMS,
                CAMERA_AND_AUDIO);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private boolean hasCameraPermission() {
        return EasyPermissions.hasPermissions(this, Manifest.permission.CAMERA);
    }

    private boolean hasAudioPermission() {
        return EasyPermissions.hasPermissions(this, Manifest.permission.RECORD_AUDIO);
    }

    private boolean hasPermissions() {
        return EasyPermissions.hasPermissions(this, CAMERA_AND_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    public void onPermissionsGranted(int requestCode, List<String> perms) {
        for (String p : perms) {
            Log.d("Permissions", "onPermissionsGranted, p =" + p);
        }
    }

    @Override
    public void onPermissionsDenied(int requestCode, List<String> perms) {
        for (String p : perms) {
            Log.d("#####Permissions", "onPermissionsGranted, p =" + p);
        }
        if (perms.size() > 0) {
            finish();
        }
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            new AppSettingsDialog.Builder(this).build().show();
        }
    }

}
