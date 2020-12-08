package cc.rome.vv.webrtc;

import android.content.Context;
import android.util.Log;
import android.view.View;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
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
import java.util.HashMap;
import java.util.List;

import cc.rome.vv.CommonConfig;
import cc.rome.vv.PeerConnectionAdapter;
import cc.rome.vv.SdpAdapter;
import cc.rome.vv.SignalingClient;

public class WebRtcManager implements SignalingClient.Callback{
    private Context context;

    private EglBase.Context eglBaseContext;
    private PeerConnectionFactory peerConnectionFactory;
    private MediaStream mediaStream;

    private SurfaceViewRenderer localView;

    private SurfaceViewRenderer[] remoteViews;
    private int remoteViewsIndex = 0;
    private String selfSocketId;

    private String roomId;

    private List<PeerConnection.IceServer> iceServers;
    private HashMap<String, PeerConnection> peerConnectionMap;

    public void init(Context context) {
        this.context = context;
        eglBaseContext = EglBase.create().getEglBaseContext();
        peerConnectionMap = new HashMap<>();
        initConfiguration();
        initPeerConnectionFactory();
    }


    private void initConfiguration() {
        iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder(CommonConfig.ICE_SERVER).createIceServer());

    }


    private void initPeerConnectionFactory() {
        // create PeerConnectionFactory
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(context)
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
    }


    public void initLocalStream(SurfaceViewRenderer localView){
        this.localView = localView;
        localView.setMirror(true);
        localView.init(eglBaseContext, null);

        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext);
        // create VideoCapturer
        VideoCapturer videoCapturer = createCameraCapturer(true);
        VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
        videoCapturer.startCapture(360, 480, 30);

        VideoTrack videoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);
        videoTrack.addSink(localView);


        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        AudioTrack audioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);


        mediaStream = peerConnectionFactory.createLocalMediaStream("mediaStream");
        mediaStream.addTrack(videoTrack);
        mediaStream.addTrack(audioTrack);
    }

    public void initRemoteViews(SurfaceViewRenderer[] remoteViews){
        this.remoteViews = remoteViews;
        for (SurfaceViewRenderer remoteView : remoteViews) {
            remoteView.setMirror(false);
            remoteView.init(eglBaseContext, null);
        }
    }

    public void startChat(){
        SignalingClient.get().init(this);
    }

    public void stopChat(){
        SignalingClient.get().leave(roomId);
    }

    private synchronized PeerConnection getOrCreatePeerConnection(String socketId) {
        PeerConnection peerConnection = peerConnectionMap.get(socketId);
        if (peerConnection != null) {
            return peerConnection;
        }
        peerConnection = peerConnectionFactory.createPeerConnection(iceServers, new PeerConnectionAdapter("PC:" + socketId) {
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
//                runOnUiThread(() -> {
                    remoteVideoTrack.addSink(remoteViews[remoteViewsIndex++]);
//                });
            }


            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                super.onIceGatheringChange(iceGatheringState);
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                Log.w("#####", "[onConnectionChange],newState =" + newState);
                for (SurfaceViewRenderer currentView : remoteViews) {
                    if (currentView.getTag() == socketId) {
                        if (currentView != null) {
//                            runOnUiThread(() -> {
                                if (newState == PeerConnection.PeerConnectionState.CLOSED) {
                                    if (remoteViewsIndex-- < 0) {
                                        remoteViewsIndex = 0;
                                    }
                                    currentView.clearImage();
                                }
//                                else if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
//                                    currentView.setVisibility(View.VISIBLE);
//                                }
//                            });
                        }
                    }
                }


            }
        });
        peerConnection.addStream(mediaStream);
        peerConnectionMap.put(socketId, peerConnection);
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
        Log.d("#########", "[onOfferReceived]");

            final String socketId = data.optString("from");
            PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
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

}
