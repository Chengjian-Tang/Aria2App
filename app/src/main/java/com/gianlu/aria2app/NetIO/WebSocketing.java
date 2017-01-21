package com.gianlu.aria2app.NetIO;

import android.app.Activity;
import android.content.Context;
import android.util.Pair;

import com.gianlu.aria2app.MainActivity;
import com.gianlu.aria2app.NetIO.JTA2.Aria2Exception;
import com.gianlu.aria2app.Utils;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFrame;
import com.neovisionaries.ws.client.WebSocketState;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WebSocketing extends WebSocketAdapter {
    private static WebSocketing webSocketing;
    private static IConnecting handler;
    private static IListener globalHandler;
    private static boolean isDestroying;
    private final Map<Integer, IReceived> requests = new ConcurrentHashMap<>();
    private final List<Pair<JSONObject, IReceived>> connectionQueue = new ArrayList<>();
    private WebSocket socket;

    private WebSocketing(Context context) throws IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException, KeyManagementException {
        socket = Utils.readyWebSocket(context)
                .addListener(this)
                .connectAsynchronously();
    }

    public static void destroyInstance() {
        if (webSocketing != null) {
            isDestroying = true;
            webSocketing.socket.disconnect();
            webSocketing = null;
        }
    }

    public static WebSocketing newInstance(Activity context) throws IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException, KeyManagementException {
        if (webSocketing == null)
            webSocketing = new WebSocketing(context);
        return webSocketing;
    }

    public static void setGlobalHandler(IListener globalHandler) {
        WebSocketing.globalHandler = globalHandler;
    }

    public static void notifyConnection(IConnecting handler) {
        if (webSocketing != null) {
            if (webSocketing.socket.getState() == WebSocketState.OPEN) {
                handler.onDone();
                return;
            }
        }
        WebSocketing.handler = handler;
    }

    public static void enableEventManager(final MainActivity mainActivity) throws IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException, KeyManagementException {
        if (mainActivity == null) return;

        newInstance(mainActivity).socket.addListener(new WebSocketAdapter() {
            @Override
            public void onFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {
                if (new JSONObject(frame.getPayloadText()).getString("method").startsWith("aria2.on"))
                    mainActivity.reloadPage();
            }
        });
    }

    public void send(JSONObject request, IReceived handler) {
        if (requests.size() > 10) {
            synchronized (requests) {
                requests.clear();
            }

            System.gc();
        }

        if (connectionQueue.size() > 10) {
            connectionQueue.clear();
            System.gc();
        }

        if (socket.getState() == WebSocketState.CONNECTING || socket.getState() == WebSocketState.CREATED) {
            connectionQueue.add(new Pair<>(request, handler));
            handler.onException(true, null);
            return;
        } else if (socket.getState() != WebSocketState.OPEN) {
            return;
        }

        try {
            requests.put(request.getInt("id"), handler);
            socket.sendText(request.toString());
        } catch (JSONException ex) {
            handler.onException(false, ex);
        } catch (Exception ignored) {
            System.gc();
        }
    }

    private void processQueue() {
        for (Pair<JSONObject, IReceived> pair : connectionQueue) {
            send(pair.first, pair.second);
        }
    }

    @Override
    public void onTextMessage(WebSocket websocket, String text) throws Exception {
        JSONObject response = new JSONObject(text);

        String method = response.optString("method");
        if (method != null && method.startsWith("aria2.on")) return;

        IReceived handler = requests.remove(response.getInt("id"));
        if (handler == null) return;
        if (response.isNull("error")) {
            handler.onResponse(response);
        } else {
            handler.onException(false, new Aria2Exception(response.getJSONObject("error").getString("message"), response.getJSONObject("error").getInt("code")));
        }
    }

    @Override
    public void onConnected(WebSocket websocket, Map<String, List<String>> headers) throws Exception {
        if (handler != null)
            handler.onDone();
    }

    @Override
    public void onStateChanged(WebSocket websocket, WebSocketState newState) throws Exception {
        if (newState.equals(WebSocketState.OPEN) && connectionQueue.size() > 0) processQueue();
    }

    @Override
    public void onError(WebSocket websocket, WebSocketException cause) throws Exception {
        if (globalHandler != null)
            globalHandler.onException(cause);

        if (handler != null)
            handler.onDone();
    }

    @Override
    public void handleCallbackError(WebSocket websocket, Throwable cause) throws Exception {
        if (cause instanceof ArrayIndexOutOfBoundsException)
            return;

        if (globalHandler != null)
            globalHandler.onException(cause);

        if (handler != null)
            handler.onDone();
    }

    @Override
    public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
        if (globalHandler != null && closedByServer)
            globalHandler.onDisconnected();

        if (isDestroying) {
            isDestroying = false;
            return;
        }

        if (handler != null)
            handler.onDone();
    }

    public interface IListener {
        void onException(Throwable ex);

        void onDisconnected();
    }

    public interface IReceived {
        void onResponse(JSONObject response) throws JSONException;

        void onException(boolean queuing, Exception ex);
    }

    public interface IConnecting {
        void onDone();
    }
}
