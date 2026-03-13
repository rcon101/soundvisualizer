#!/bin/bash
set -e

# ── WSL2 / WSLg audio setup ────────────────────────────────────────────────
# Route Java's ALSA backend through the WSLg PulseAudio server so that
# real system audio sources (microphone, RDPSink.monitor loopback) are visible.
WSLG_PULSE_SOCKET="/mnt/wslg/PulseServer"
if [ -S "$WSLG_PULSE_SOCKET" ] || [ -e "$WSLG_PULSE_SOCKET" ]; then
    export PULSE_SERVER="unix:$WSLG_PULSE_SOCKET"
    echo "[audio] WSLg PulseAudio detected — PULSE_SERVER=$PULSE_SERVER"

    # Create ~/.asoundrc if it doesn't already route to PulseAudio
    ASOUNDRC="$HOME/.asoundrc"
    if [ ! -f "$ASOUNDRC" ] || ! grep -q 'type pulse' "$ASOUNDRC" 2>/dev/null; then
        echo "[audio] Writing ~/.asoundrc (ALSA → PulseAudio bridge with named sources)"
        cat > "$ASOUNDRC" << 'ASOUNDRC_EOF'
pcm.!default {
    type pulse
    hint.description "Default Audio (PulseAudio)"
}
ctl.!default {
    type pulse
}
# System audio loopback — captures all Windows audio output
pcm.system_monitor {
    type pulse
    device "RDPSink.monitor"
    hint.description "System Audio Monitor (all programs)"
    hint.show on
}
ctl.system_monitor { type pulse }
# Microphone / RDP audio input
pcm.rdp_mic {
    type pulse
    device "RDPSource"
    hint.description "Microphone / RDP Input"
    hint.show on
}
ctl.rdp_mic { type pulse }
ASOUNDRC_EOF
    fi
fi
# ───────────────────────────────────────────────────────────────────────────

make all
java -cp bin com.soundvisualizer.AppSoundVisualizer