package io.openvidu.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.headers
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import io.openvidu.android.databinding.ActivityRoomLayoutBinding
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
//import io.livekit.android.room.track.publication.AudioTrackPublication


// TrackInfo model

data class TrackInfo(
    val track: VideoTrack,
    val participantIdentity: String,
    val isLocal: Boolean = false
)

class RoomLayoutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRoomLayoutBinding
    private lateinit var participantAdapter: ParticipantAdapter

    private lateinit var room: Room
    private val participantTracks: MutableList<TrackInfo> = mutableListOf()

    private val client = HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) {
            json()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoomLayoutBinding.inflate(layoutInflater)
        supportActionBar?.hide()
        setContentView(binding.root)

        binding.loader.visibility = View.VISIBLE
        binding.leaveButton.setOnClickListener {
            leaveRoom()
        }

        // Create Room object
        room = LiveKit.create(applicationContext)

        initRecyclerView()
        requestNeededPermissions { connectToRoom() }
    }

    private fun initRecyclerView() {
        participantAdapter = ParticipantAdapter(participantTracks, room)
        binding.participants.layoutManager = LinearLayoutManager(this)
        binding.participants.adapter = participantAdapter
    }

    private fun connectToRoom() {
        val participantName = intent.getStringExtra("participantName") ?: "Participant1"
        val roomName = intent.getStringExtra("roomName") ?: "Test Room"

        binding.roomName.text = roomName

        lifecycleScope.launch {
            // Specify the actions when events take place in the room
            launch {
                room.events.collect { event ->
                    when (event) {
                        // On every new Track received...
                        is RoomEvent.TrackSubscribed -> onTrackSubscribed(event)
                        // On every new Track destroyed...
                        is RoomEvent.TrackUnsubscribed -> onTrackUnsubscribed(event)
                        else -> {}
                    }
                }
            }

            try {
                val token = getToken(roomName, participantName)
                room.connect(Urls.livekitUrl, token)

                // Publish your camera and microphone
                val localParticipant = room.localParticipant
                localParticipant.setMicrophoneEnabled(true)
                val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true

                localParticipant.setCameraEnabled(true)

                // Add local video track to the participantTracks list
                launch {
                    localParticipant::videoTrackPublications.flow
                        .collect { publications ->
                            val videoTrack = publications.firstOrNull()?.second as? VideoTrack

                            if (videoTrack != null) {
                                participantTracks.add(
                                    0,
                                    TrackInfo(videoTrack, participantName, true)
                                )
                                participantAdapter.notifyItemInserted(0)
                            }
                        }
                }

                binding.loader.visibility = View.GONE
            } catch (e: Exception) {
                println("There was an error connecting to the room: ${e.message}")
                Toast.makeText(this@RoomLayoutActivity, "Failed to join room", Toast.LENGTH_SHORT)
                    .show()
                leaveRoom()
            }
        }
    }

    private fun onTrackSubscribed(event: RoomEvent.TrackSubscribed) {
        val track = event.track
        if (track is VideoTrack) {
            participantTracks.add(TrackInfo(track, event.participant.identity!!.value))
            participantAdapter.notifyItemInserted(participantTracks.size - 1)
        }
        if (track is AudioTrack) {
            binding.waveView.visibility = View.VISIBLE
            binding.waveView.playAnimation()
        }
    }

    private fun onTrackUnsubscribed(event: RoomEvent.TrackUnsubscribed) {
        val track = event.track

        // If the track is a video track, remove it from the participantTracks list
        if (track is VideoTrack) {
            val index = participantTracks.indexOfFirst { it.track.sid == track.sid }

            if (index != -1) {
                participantTracks.removeAt(index)
                participantAdapter.notifyItemRemoved(index)
            }
        }
        if (track is AudioTrack) {
            binding.waveView.cancelAnimation()
            binding.waveView.visibility = View.GONE
        }
    }

    private fun leaveRoom() {
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        audioManager.mode = android.media.AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        room.disconnect()
        client.close()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        leaveRoom()
    }

    private fun requestNeededPermissions(onHasPermissions: () -> Unit) {
        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                var hasDenied = false
                for (grant in grants.entries) {
                    if (!grant.value) {
                        Toast.makeText(this, "Missing permission: ${grant.key}", Toast.LENGTH_SHORT).show()
                        hasDenied = true
                    }
                }
                if (!hasDenied) {
                    onHasPermissions()
                }
            }

        // Assemble the needed permissions to request
        val neededPermissions =
            listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA).filter {
                ContextCompat.checkSelfPermission(
                    this, it
                ) == PackageManager.PERMISSION_DENIED
            }.toTypedArray()

        if (neededPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(neededPermissions)
        } else {
            onHasPermissions()
        }
    }

    private suspend fun getToken(roomName: String, participantName: String): String {
        val response = client.post(Urls.applicationServerUrl) {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(participantName, roomName))
            headers {
                append("X-Sandbox-ID", "agile-virtualmachine-d6o8j3")
            }
        }
        return response.body<TokenResponse>().token
    }
}

@Serializable
data class TokenRequest(val participantName: String, val roomName: String)

@Serializable
data class TokenResponse(
    @SerialName("participantToken") val token: String,
    @SerialName("serverUrl") val serverUrl: String,
    @SerialName("roomName") val roomName: String,
    @SerialName("participantName") val participantName: String
)
