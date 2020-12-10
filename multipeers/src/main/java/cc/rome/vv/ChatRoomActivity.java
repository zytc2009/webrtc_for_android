package cc.rome.vv;

import android.Manifest;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.DataChannel;
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;

public class ChatRoomActivity extends AppCompatActivity implements SignalingClient.Callback, EasyPermissions.PermissionCallbacks, View.OnClickListener {

    private EglBase.Context eglBaseContext;
    private PeerConnectionFactory peerConnectionFactory;
    private List<PeerConnection.IceServer> iceServers;

    private HashMap<String, PeerConnection> peerConnectionMap;
    private HashMap<String, DataChannel> channelHashMap;

    private String selfSocketId;
    private String roomId;

    private static final String[] CAMERA_AND_AUDIO = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    private static final int RC_PERMS = 100;

    private TextView messageView;
    private EditText message_input;

    private DataChannel dataChannel;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chatroom);

        initView();

        initWebRtc();
    }

    private void initView(){
        messageView = findViewById(R.id.messageView);
        message_input = findViewById(R.id.message_input);

        findViewById(R.id.btn_send).setOnClickListener(this);

    }





    @AfterPermissionGranted(RC_PERMS)
    private void initWebRtc() {
        peerConnectionMap = new HashMap<>();
        channelHashMap = new HashMap<>();

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
//                .setVideoEncoderFactory(defaultVideoEncoderFactory)
//                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory();



        Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE);//打开webrtc的log输出

        SignalingClient.get().init(this);
    }



    private synchronized PeerConnection getOrCreatePeerConnection(String socketId) {
        PeerConnection peerConnection = peerConnectionMap.get(socketId);
        if (peerConnection != null) {
            return peerConnection;
        }
        peerConnection = peerConnectionFactory.createPeerConnection(iceServers, new PeerConnectionAdapter("PC:" + socketId) {
            @Override
            public void onDataChannel(DataChannel dataChannel) {
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                Log.w("#####", "onIceCandidate，sdp" + iceCandidate.sdp);
                SignalingClient.get().sendIceCandidate(iceCandidate, socketId);
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

        initChannel(peerConnection, socketId);

        peerConnectionMap.put(socketId, peerConnection);
//        channelHashMap.put(socketId, channel);

        return peerConnection;
    }

    @Override
    public void onCreateRoom(String roomId, String socketId) {
        Log.d("#########", "[onCreateRoom], 房间创建成功，roomId =" + roomId + ", 您的socketId = " + socketId);
        this.selfSocketId = socketId;
        this.roomId = roomId;
    }

    @Override
    public void onSelfJoined(String roomId, String socketId) {
        //针对新加入者，不是房间创建者，房间创建者回调onCreateRoom
        Log.d("#########", "[onSelfJoined], 您已经加入房间，房间roomId =" + roomId + ", 您的socketId = " + socketId);
        this.selfSocketId = socketId;
        this.roomId = roomId;
    }

    @Override
    public void onPeerJoined(String roomId, String socketId) {
        this.roomId = roomId;
        Log.d("#########", "[onPeerJoined],有新加入者，socketId=" + socketId);
        PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
//        initChannel(peerConnection, socketId);

        peerConnection.createOffer(new SdpAdapter("createOfferSdp:" + socketId) {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                peerConnection.setLocalDescription(new SdpAdapter("setLocalSdp:" + socketId), sessionDescription);
                SignalingClient.get().sendSessionDescription(sessionDescription, socketId);
            }
        }, new MediaConstraints());
    }

    @Override
    public void onOfferReceived(JSONObject data) {
        Log.d("#########", "[onOfferReceived] data=" + data);
        final String socketId = data.optString("from");
        PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
//        initChannel(peerConnection, socketId);

        peerConnection.setRemoteDescription(new SdpAdapter("setRemoteSdp:" + socketId),
                new SessionDescription(SessionDescription.Type.OFFER, data.optString("sdp")));
        peerConnection.createAnswer(new SdpAdapter("localAnswerSdp") {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                super.onCreateSuccess(sdp);
                peerConnectionMap.get(socketId).setLocalDescription(new SdpAdapter("setLocalSdp:" + socketId), sdp);
                SignalingClient.get().sendSessionDescription(sdp, socketId);
            }
        }, new MediaConstraints());
    }

    private void initChannel(PeerConnection peerConnection, String socketId){
        DataChannel.Init init = new DataChannel.Init();
        init.ordered = true;
        init.negotiated=true;
        init.maxRetransmits=-1;
        init.maxRetransmitTimeMs=-1;
        init.id = 0;
        Log.d("#########", "[DataChannel] initChannel() socketId =" + socketId);
        dataChannel = peerConnection.createDataChannel("textchat", init);
        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long l) {

            }

            @Override
            public void onStateChange() {
                Log.d("#########", "[DataChannel] onStateChange() state =" + dataChannel.state());
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                try {
                    byte[] data = new byte[buffer.data.limit()];//buffer.data.capacity()
                    buffer.data.get(data);
                    Log.w("#####", "[onMessage]DataChannel,buffer =" + new String(data));
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
        });
        channelHashMap.put(socketId, dataChannel);
    }

    @Override
    public void onAnswerReceived(JSONObject data) {
        Log.d("#########", "[onAnswerReceived]" + data);
        String socketId = data.optString("from");
        PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
        peerConnection.setRemoteDescription(new SdpAdapter("setRemoteSdp:" + socketId),
                new SessionDescription(SessionDescription.Type.ANSWER, data.optString("sdp")));
    }

    @Override
    public void onIceCandidateReceived(JSONObject data) {
        Log.d("#########", "onIceCandidateReceived=" + data);
        String socketId = data.optString("from");
        PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
        peerConnection.addIceCandidate(new IceCandidate(
                data.optString("id"),
                data.optInt("label"),
                data.optString("candidate")
        ));
    }

    @Override
    public void onPeerLeave(String roomId, String socketId) {
        Log.e("#####[onPeerLeave]", "有人离开房间 " + roomId + ",离去者是 " + socketId);
        PeerConnection peerConnection = peerConnectionMap.get(socketId);
        if (peerConnection != null) {
            peerConnection.close();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SignalingClient.get().leave(roomId);
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

    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.btn_send:
                sendMessage();
                break;
        }
    }

    void sendMessage(){
        String message = message_input.getEditableText().toString();
        ByteBuffer byteBuffer = ByteBuffer.wrap(message.getBytes());
        DataChannel.Buffer buffer = new DataChannel.Buffer(byteBuffer, false);

//        SignalingClient.get().sendMessage(message);
        dataChannel.send(buffer);
    }
}
