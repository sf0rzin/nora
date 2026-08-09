import io
import json
from unittest.mock import Mock, patch
import pytest

from nora_stt_sidecar.transcriber import LiveTranscriber
from nora_stt_sidecar.protocol import (
    ReadyMessage,
    PartialMessage,
    FinalMessage,
    ErrorMessage,
    StoppedMessage,
)


class FakeResult:
    """Fake speech recognition result."""
    def __init__(self, text, offset=0, duration=0, speaker_id=None, confidence=None):
        self.text = text
        self.offset = offset
        self.duration = duration
        self.speaker_id = speaker_id
        # The real code reads confidence from the detailed JSON (NBest[0].Confidence),
        # not from a .confidence attribute (which the SDK does not expose). Audit #112.
        self.json = (
            json.dumps({"NBest": [{"Confidence": confidence}]})
            if confidence is not None
            else "{}"
        )


class FakeEventArgs:
    """Fake event args."""
    def __init__(self, result):
        self.result = result


class TestTranscriber:
    """Test LiveTranscriber with mocked Azure Speech SDK."""
    
    @patch("nora_stt_sidecar.transcriber.SpeechConfig")
    @patch("nora_stt_sidecar.transcriber.ConversationTranscriber")
    @patch("nora_stt_sidecar.transcriber.PushAudioInputStream")
    @patch("nora_stt_sidecar.transcriber.AudioStreamFormat")
    @patch("nora_stt_sidecar.transcriber.AudioConfig")
    def test_start_emits_ready(
        self,
        mock_audio_config,
        mock_stream_format,
        mock_push_stream,
        mock_transcriber_class,
        mock_speech_config,
    ):
        events = []
        
        def on_event(msg):
            events.append(msg)
        
        transcriber = LiveTranscriber(
            session_id="test",
            region="eastus",
            auth_token="ey.jwt.fake",
            on_event=on_event,
        )
        
        # Mock the transcriber instance
        mock_transcriber = Mock()
        mock_transcriber_class.return_value = mock_transcriber
        mock_transcriber.start_transcribing_async.return_value.get.return_value = None
        
        transcriber.start()
        
        assert len(events) == 1
        assert isinstance(events[0], ReadyMessage)
        assert events[0].session_id == "test"
    
    @patch("nora_stt_sidecar.transcriber.SpeechConfig")
    @patch("nora_stt_sidecar.transcriber.ConversationTranscriber")
    @patch("nora_stt_sidecar.transcriber.PushAudioInputStream")
    @patch("nora_stt_sidecar.transcriber.AudioStreamFormat")
    @patch("nora_stt_sidecar.transcriber.AudioConfig")
    def test_transcribing_emits_partial(
        self,
        mock_audio_config,
        mock_stream_format,
        mock_push_stream,
        mock_transcriber_class,
        mock_speech_config,
    ):
        events = []
        
        def on_event(msg):
            events.append(msg)
        
        transcriber = LiveTranscriber(
            session_id="test",
            region="eastus",
            auth_token="ey.jwt.fake",
            on_event=on_event,
        )
        
        mock_transcriber = Mock()
        mock_transcriber_class.return_value = mock_transcriber
        mock_transcriber.start_transcribing_async.return_value.get.return_value = None
        
        transcriber.start()
        
        # Simulate transcribing event
        fake_result = FakeResult(
            text="Hello",
            offset=10000000,  # 1000ms in 100-nanosecond units
            speaker_id="Guest-1",
        )
        fake_args = FakeEventArgs(fake_result)
        
        # Call the handler directly
        transcriber._on_transcribing(fake_args)
        
        assert len(events) == 2  # ready + partial
        assert isinstance(events[1], PartialMessage)
        assert events[1].text == "Hello"
        assert events[1].speaker_id == "Guest-1"
        assert events[1].offset_ms == 1000
    
    @patch("nora_stt_sidecar.transcriber.SpeechConfig")
    @patch("nora_stt_sidecar.transcriber.ConversationTranscriber")
    @patch("nora_stt_sidecar.transcriber.PushAudioInputStream")
    @patch("nora_stt_sidecar.transcriber.AudioStreamFormat")
    @patch("nora_stt_sidecar.transcriber.AudioConfig")
    def test_transcribed_emits_final(
        self,
        mock_audio_config,
        mock_stream_format,
        mock_push_stream,
        mock_transcriber_class,
        mock_speech_config,
    ):
        events = []
        
        def on_event(msg):
            events.append(msg)
        
        transcriber = LiveTranscriber(
            session_id="test",
            region="eastus",
            auth_token="ey.jwt.fake",
            on_event=on_event,
        )
        
        mock_transcriber = Mock()
        mock_transcriber_class.return_value = mock_transcriber
        mock_transcriber.start_transcribing_async.return_value.get.return_value = None
        
        transcriber.start()
        
        # Simulate transcribed event
        fake_result = FakeResult(
            text="Hello world",
            offset=10000000,
            duration=5000000,
            speaker_id="Guest-1",
            confidence=0.95,
        )
        fake_args = FakeEventArgs(fake_result)
        
        transcriber._on_transcribed(fake_args)
        
        assert len(events) == 2
        assert isinstance(events[1], FinalMessage)
        assert events[1].text == "Hello world"
        assert events[1].duration_ms == 500
        assert events[1].confidence == 0.95
    
    @patch("nora_stt_sidecar.transcriber.SpeechConfig")
    @patch("nora_stt_sidecar.transcriber.ConversationTranscriber")
    @patch("nora_stt_sidecar.transcriber.PushAudioInputStream")
    @patch("nora_stt_sidecar.transcriber.AudioStreamFormat")
    @patch("nora_stt_sidecar.transcriber.AudioConfig")
    def test_stop_emits_stopped(
        self,
        mock_audio_config,
        mock_stream_format,
        mock_push_stream,
        mock_transcriber_class,
        mock_speech_config,
    ):
        events = []
        
        def on_event(msg):
            events.append(msg)
        
        transcriber = LiveTranscriber(
            session_id="test",
            region="eastus",
            auth_token="ey.jwt.fake",
            on_event=on_event,
        )
        
        mock_transcriber = Mock()
        mock_transcriber_class.return_value = mock_transcriber
        mock_transcriber.start_transcribing_async.return_value.get.return_value = None
        mock_transcriber.stop_transcribing_async.return_value.get.return_value = None
        
        transcriber.start()
        transcriber.stop()
        
        assert len(events) == 2  # ready + stopped
        assert isinstance(events[1], StoppedMessage)
