WIN_JAVA   := /mnt/c/Program Files/Java/jdk-24/bin/java.exe
MAIN_CLASS := com.soundvisualizer.AppSoundVisualizer
SRC        := src/java/com/soundvisualizer
BIN        := bin

.PHONY: all run native clean

all:
	@mkdir -p $(BIN)
	javac --release 17 -d $(BIN) $(SRC)/*.java

# Set PULSE_SERVER so Java's ALSA backend can reach the WSLg PulseAudio server
PULSE_SOCKET := /mnt/wslg/PulseServer

run: all
	@if [ -e "$(PULSE_SOCKET)" ]; then \
		export PULSE_SERVER=unix:$(PULSE_SOCKET); \
		if ! grep -q 'type pulse' $(HOME)/.asoundrc 2>/dev/null; then \
			echo "[audio] Creating ~/.asoundrc for ALSA→PulseAudio routing"; \
			printf 'pcm.!default {\n    type pulse\n}\nctl.!default {\n    type pulse\n}\n' > $(HOME)/.asoundrc; \
		fi; \
		echo "[audio] PULSE_SERVER=$$PULSE_SERVER"; \
		PULSE_SERVER=unix:$(PULSE_SOCKET) java -cp $(BIN) $(MAIN_CLASS); \
	else \
		java -cp $(BIN) $(MAIN_CLASS); \
	fi

native: all
	"$(WIN_JAVA)" -cp "$$(wslpath -w $$(pwd)/$(BIN))" $(MAIN_CLASS)

clean:
	rm -rf $(BIN)/*