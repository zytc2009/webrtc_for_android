package cc.rome.vv;

import android.Manifest;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
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
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;


/**
 * 本地模拟1vs1视频对话
 */
public class LocalVideoActivity extends AppCompatActivity implements EasyPermissions.PermissionCallbacks {

    private EglBase.Context eglBaseContext;
    private PeerConnectionFactory peerConnectionFactory;
    private List<PeerConnection.IceServer> iceServers;

    private MediaStream mediaStream;
    private MediaStream remoteStream;

    PeerConnection localPeer;
    PeerConnection remotePeer;

    private String selfSocketId = "120";
    private String remoteSocketId = "150";

    private static final String[] CAMERA_AND_AUDIO = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    private static final int RC_PERMS = 100;

    private SurfaceViewRenderer localView;
    private SurfaceViewRenderer remoteView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_localview);

        initView();
        if (!hasPermissions()) {
            requestPermissions();
        } else {
            initWebRtc();
        }
    }

    private void initView(){
        localView = findViewById(R.id.localView);
        remoteView = findViewById(R.id.remoteView);
    }


    @AfterPermissionGranted(RC_PERMS)
    private void initWebRtc() {
        iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder(CommonConfig.ICE_SERVER).createIceServer());

        eglBaseContext = EglBase.create().getEglBaseContext();

        // create PeerConnectionFactory
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(this)
                .createInitializationOptions());
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory defaultVideoEncoderFactory =
                new DefaultVideoEncoderFactory(eglBaseContext, false, false);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory =
                new DefaultVideoDecoderFactory(eglBaseContext);

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory();

        //初始化本地流
        initLocalStream(localView);
    }

    public void initLocalStream(SurfaceViewRenderer localView){

        localView.setMirror(true);
        localView.init(eglBaseContext, null);

        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext);
        // create VideoCapturer
        VideoCapturer videoCapturer = createCameraCapturer(true);
        VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, this, videoSource.getCapturerObserver());
        videoCapturer.startCapture(360, 480, 30);

        VideoTrack videoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);
        videoTrack.addSink(localView);

        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        AudioTrack audioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);

        mediaStream = peerConnectionFactory.createLocalMediaStream("mediaStream");
        mediaStream.addTrack(videoTrack);
        mediaStream.addTrack(audioTrack);


        remoteView.setMirror(false);
        remoteView.init(eglBaseContext, null);
    }

    //模拟呼叫
    public void startCall(View view){
        localPeer = createLocalPeerConnection(selfSocketId);
        remotePeer = createRemotePeerConnection(remoteSocketId);

        localPeer.addStream(mediaStream);

        localPeer.createOffer(new SdpAdapter("createOfferSdp:" + selfSocketId) {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                Log.d("#########", "[createOffer]");
                localPeer.setLocalDescription(new SdpAdapter("setLocalSdp:" + selfSocketId), sessionDescription);
                remotePeer.setRemoteDescription(new SdpAdapter("setLocalSdp:" + selfSocketId), sessionDescription);

                onOfferReceived();
            }
        }, new MediaConstraints());

    }

    private VideoCapturer createCameraCapturer(boolean isFront) {
        // Have permission, do the thing!
        Camera1Enumerator enumerator = new Camera1Enumerator(false);
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        for (String deviceName : deviceNames) {
            if (isFront ? enumerator.isFrontFacing(deviceName) : enumerator.isBackFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        return null;
    }

    private synchronized PeerConnection createLocalPeerConnection(String socketId) {
        PeerConnection peerConnection = peerConnectionFactory.createPeerConnection(iceServers, new PeerConnectionAdapter("PC:" + socketId) {
            @Override
            public void onDataChannel(DataChannel dataChannel) {
                super.onDataChannel(dataChannel);
                Log.w("#####", "[DataChannel] onDataChannel()" );
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                Log.w("#####", "onIceCandidate，sdp" + iceCandidate.sdp);
                //添加到对方，需要通过信令服务发给对方，这里省去了信令交换
                remotePeer.addIceCandidate(iceCandidate);
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.w("#####", "[onAddStream]");
                VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
            }


            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                super.onIceGatheringChange(iceGatheringState);
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                Log.w("#####", "[onConnectionChange],newState =" + newState);
            }
        });

        return peerConnection;
    }

    //模拟对端
    private synchronized PeerConnection createRemotePeerConnection(String socketId) {
        PeerConnection peerConnection = peerConnectionFactory.createPeerConnection(iceServers, new PeerConnectionAdapter("PC:" + socketId) {
            @Override
            public void onDataChannel(DataChannel dataChannel) {
                super.onDataChannel(dataChannel);
                Log.w("#####", "[DataChannel] onDataChannel()" );
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                Log.w("#####", "onIceCandidate，sdp" + iceCandidate.sdp);
                //添加到对方，需要通过信令服务发给对方，这里省去了信令交换
                localPeer.addIceCandidate(iceCandidate);
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.w("#####", "[onAddStream]");

                VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
                remoteVideoTrack.addSink(remoteView);

                remoteStream = mediaStream;
            }


            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                super.onIceGatheringChange(iceGatheringState);
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                Log.w("#####", "[onConnectionChange],newState =" + newState);
            }
        });

        return peerConnection;
    }


    public void onOfferReceived() {
        remotePeer.createAnswer(new SdpAdapter("localAnswerSdp") {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                super.onCreateSuccess(sdp);
                Log.d("#########", "[createOffer]");
                remotePeer.setLocalDescription(new SdpAdapter("setLocalSdp:" + remoteSocketId), sdp);
                localPeer.setRemoteDescription(new SdpAdapter("setLocalSdp:" + remoteSocketId), sdp);
            }
        }, new MediaConstraints());

    }

    public void hunUp(View view) {
        localPeer.close();
        remotePeer.close();
    }

    @Override
    protected void onDestroy() {
        hunUp(null);
        super.onDestroy();
    }


    private boolean hasPermissions() {
        return EasyPermissions.hasPermissions(this, CAMERA_AND_AUDIO);
    }


    private void requestPermissions() {
        EasyPermissions.requestPermissions(
                this,
                "使用前需要您授权开启摄像头和麦克风",
                RC_PERMS,
                CAMERA_AND_AUDIO);
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
