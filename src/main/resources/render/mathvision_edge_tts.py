"""Manim Voiceover speech service backed by the free Edge online TTS endpoint."""

from pathlib import Path

import edge_tts
from manim_voiceover.helper import remove_bookmarks
from manim_voiceover.services.base import SpeechService


class EdgeTTSService(SpeechService):
    """Synthesize voiceovers with ``edge-tts`` while preserving Manim's cache contract."""

    def __init__(
        self,
        voice="zh-CN-XiaoxiaoNeural",
        rate="+0%",
        volume="+0%",
        pitch="+0Hz",
        proxy=None,
        connect_timeout=10,
        receive_timeout=60,
        lang=None,
        **kwargs,
    ):
        super().__init__(**kwargs)
        self.voice = voice
        self.rate = rate
        self.volume = volume
        self.pitch = pitch
        self.proxy = proxy
        self.connect_timeout = connect_timeout
        self.receive_timeout = receive_timeout
        # ``lang`` is accepted only so legacy GTTSService calls can be migrated
        # without changing their remaining constructor arguments.
        self.lang = lang

    def generate_from_text(
        self,
        text: str,
        cache_dir: str = None,
        path: str = None,
        **kwargs,
    ) -> dict:
        cache_path = Path(cache_dir) if cache_dir is not None else Path(self.cache_dir)
        cache_path.mkdir(parents=True, exist_ok=True)

        input_text = remove_bookmarks(text)
        voice = kwargs.pop("voice", self.voice)
        rate = kwargs.pop("rate", self.rate)
        volume = kwargs.pop("volume", self.volume)
        pitch = kwargs.pop("pitch", self.pitch)
        proxy = kwargs.pop("proxy", self.proxy)
        connect_timeout = kwargs.pop("connect_timeout", self.connect_timeout)
        receive_timeout = kwargs.pop("receive_timeout", self.receive_timeout)
        # Ignore the gTTS-only language parameter when rendering a migrated
        # artifact. The selected Edge voice already determines the language.
        kwargs.pop("lang", None)
        if kwargs:
            unsupported = ", ".join(sorted(kwargs))
            raise TypeError(f"Unsupported EdgeTTSService arguments: {unsupported}")

        input_data = {
            "input_text": input_text,
            "service": "edge-tts",
            "voice": voice,
            "rate": rate,
            "volume": volume,
            "pitch": pitch,
            "global_speed": self.global_speed,
        }
        cached_result = self.get_cached_result(input_data, cache_path)
        if cached_result is not None:
            return cached_result

        audio_path = path or (self.get_audio_basename(input_data) + ".mp3")
        target_path = cache_path / audio_path
        target_path.parent.mkdir(parents=True, exist_ok=True)

        communicator = edge_tts.Communicate(
            input_text,
            voice=voice,
            rate=rate,
            volume=volume,
            pitch=pitch,
            proxy=proxy,
            connect_timeout=connect_timeout,
            receive_timeout=receive_timeout,
        )
        try:
            communicator.save_sync(str(target_path))
        except Exception as exc:
            raise RuntimeError(
                "Edge TTS synthesis failed. Check access to "
                "speech.platform.bing.com and the selected voice."
            ) from exc

        if not target_path.is_file() or target_path.stat().st_size == 0:
            raise RuntimeError("Edge TTS synthesis completed without producing audio.")

        return {
            "input_text": text,
            "input_data": input_data,
            "original_audio": audio_path,
        }
