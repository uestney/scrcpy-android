package io.github.miuzarte.scrcpyforandroid.udp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP 控制发送器：将触摸/键盘事件通过 UDP 发送到服务器
 *
 * 协议格式（JSON）：
 * - 触摸事件: {"type":"touch","action":"down/up/move","x":540,"y":960,"pointerId":0}
 * - 键盘事件: {"type":"key","action":"down/up","keyCode":4}
 * - 滚动事件: {"type":"scroll","x":540,"y":960,"deltaX":0,"deltaY":-100}
 */
class UdpControlSender(
    private val serverIp: String,
    private val controlPort: Int = 59155,  // 与 ClientOptions 默认值匹配
) {
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var isConnected = false

    companion object {
        private const val TAG = "UdpControlSender"
        private const val MAX_PACKET_SIZE = 1400 // 避免 UDP 分片
    }

    /**
     * 连接到服务器
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            socket = DatagramSocket()
            serverAddress = InetAddress.getByName(serverIp)
            isConnected = true
            Log.i(TAG, "Connected to $serverIp:$controlPort")

            // 发送心跳确认连接
            sendHeartbeat()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            isConnected = false
            throw e
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        isConnected = false
        socket?.close()
        socket = null
        Log.i(TAG, "Disconnected")
    }

    /**
     * 发送触摸事件
     */
    fun sendTouchEvent(
        action: TouchAction,
        x: Int,
        y: Int,
        pointerId: Int = 0,
    ) {
        val json = JSONObject().apply {
            put("type", "touch")
            put("action", action.value)
            put("x", x)
            put("y", y)
            put("pointerId", pointerId)
        }
        send(json)
    }

    /**
     * 发送键盘事件
     */
    fun sendKeyEvent(
        action: KeyAction,
        keyCode: Int,
    ) {
        val json = JSONObject().apply {
            put("type", "key")
            put("action", action.value)
            put("keyCode", keyCode)
        }
        send(json)
    }

    /**
     * 发送滚动事件
     */
    fun sendScrollEvent(
        x: Int,
        y: Int,
        deltaX: Float = 0f,
        deltaY: Float = 0f,
    ) {
        val json = JSONObject().apply {
            put("type", "scroll")
            put("x", x)
            put("y", y)
            put("deltaX", deltaX)
            put("deltaY", deltaY)
        }
        send(json)
    }

    /**
     * 发送 JSON 数据
     */
    private fun send(json: JSONObject) {
        if (!isConnected || socket == null || serverAddress == null) {
            Log.w(TAG, "Not connected, dropping packet")
            return
        }

        try {
            val data = json.toString().toByteArray(Charsets.UTF_8)
            if (data.size > MAX_PACKET_SIZE) {
                Log.w(TAG, "Packet too large: ${data.size} bytes")
                return
            }

            val packet = DatagramPacket(data, data.size, serverAddress, controlPort)
            socket?.send(packet)

            Log.d(TAG, "Sent: $json")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send", e)
        }
    }

    /**
     * 发送心跳
     */
    private fun sendHeartbeat() {
        val json = JSONObject().apply {
            put("type", "heartbeat")
        }
        send(json)
    }

    /**
     * 触摸动作
     */
    enum class TouchAction(val value: String) {
        DOWN("down"),
        UP("up"),
        MOVE("move"),
    }

    /**
     * 键盘动作
     */
    enum class KeyAction(val value: String) {
        DOWN("down"),
        UP("up"),
    }
}
