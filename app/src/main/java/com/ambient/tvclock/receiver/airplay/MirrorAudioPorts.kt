package com.ambient.tvclock.receiver.airplay

/**
 * UDP ports for AirPlay mirror session audio (UxPlay legacy: -p udp 7011 6001 6000).
 * data = RTP audio in, control = resend/control channel.
 */
object MirrorAudioPorts {
    const val DATA_PORT = 6000
    const val CONTROL_PORT = 6001
}
