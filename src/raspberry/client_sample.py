import asyncio
import os
import struct
import json
import logging
from pathlib import Path
from paho.mqtt import client as mqtt
import threading
import time
import global_vars
import sounddevice as sd
import numpy as np
import queue
import init_process

MQTT_HOST = "broker.vietbot.vn"
MQTT_PORT = 1883
MQTT_USER = "admin"
MQTT_PASS = "vietbot123"
GROUP = "loapay"
GROUP_NAME = "dev"
HW_ID = "e45f018959bd"
TOPIC_PREFIX = f"{GROUP}/{GROUP_NAME}/{HW_ID}"
UPLOAD_TOPIC = f"{TOPIC_PREFIX}/upload"
STATE_TOPIC = f"{TOPIC_PREFIX}/client_msg"
SERVER_STATE_TOPIC = f"{TOPIC_PREFIX}/server_msg"

CHUNK = 512
RATE = 16000
CHANNELS = 1
RECORD_SECONDS = 6
PACKET_SIZE = 10240

# Logger setup
logging.basicConfig(
    level=logging.INFO,
    format='[%(asctime)s] [%(levelname)s] %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger("Client")

client = mqtt.Client()
client.username_pw_set(MQTT_USER, MQTT_PASS)
stop_streaming_event = asyncio.Event()
audio_queue = queue.Queue()
recorder = global_vars.micRecorder
result_future = None


def on_connect(client, userdata, flags, rc):
    logger.info(f"Connected to MQTT with code {rc}")
    client.subscribe(SERVER_STATE_TOPIC)


def on_message(client, userdata, msg):
    global result_future
    try:
        if msg.topic == SERVER_STATE_TOPIC:
            payload = json.loads(msg.payload.decode())
            state = payload.get("state")

            if state == "finish_transcoding":
                logger.info(f"[Client] Server finished STT. {payload.get('request')}")
                stop_streaming_event.set()

            elif state == "wait_for_text_processing":
                logger.info("Server asked to wait.")
                global_vars.player.playback(global_vars.SOUND_FINISH, True)

            elif state == "finish_text_process":
                logger.info(f"Server finished processing, result: {payload.get('answer')}")
                global_vars.player.playback(global_vars.SOUND_FINISH, True)

            elif state == "finish_send":
                logger.info("[Client] Server completed sending TTS.")
                answer = payload.get("answer")
                answer_link = payload.get("answer_link")
                music_link = payload.get("music_link")
                if result_future and not result_future.done():
                    result_future.set_result((answer, answer_link, music_link))

    except Exception as e:
        logger.error(f"[Client] Error in on_message: {e}")


async def connect():
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(MQTT_HOST, MQTT_PORT, 60)
    loop = asyncio.get_event_loop()
    loop.run_in_executor(None, client.loop_forever)


async def publish_state(payload):
    payload_json = json.dumps(payload)
    client.publish(STATE_TOPIC, payload_json)
    logger.info(f"Published client state: {payload}")


async def publish_audio(payload):
    client.publish(UPLOAD_TOPIC, payload)
    logger.debug(f"Published audio packet of size {len(payload)}")


async def process():
    global result_future
    buffer = bytearray()
    seq = 0
    start_time = asyncio.get_event_loop().time()
    result_future = asyncio.get_event_loop().create_future()

    try:
        await publish_state({"state": "start_send", "package_size": PACKET_SIZE})
        while asyncio.get_event_loop().time() - start_time <= RECORD_SECONDS:
            pcm = recorder.read()[0]
            frame_bytes = struct.pack('<' + 'h' * len(pcm), *pcm)
            buffer.extend(frame_bytes)

            if len(buffer) >= PACKET_SIZE:
                payload = struct.pack(">I", seq) + buffer[:PACKET_SIZE]
                await publish_audio(payload)
                seq += 1
                buffer = buffer[PACKET_SIZE:]
            await asyncio.sleep(0)

        if buffer:
            payload = struct.pack(">I", seq) + buffer
            await publish_audio(payload)

        await publish_state({"state": "finish_send"})

        try:
            result = await asyncio.wait_for(result_future, timeout=10.0)
            logger.info(f"[Client] Received result from server: {result}")
            print(result)
        except asyncio.TimeoutError:
            logger.warning("[Client] Timeout waiting for server result.")
            print("[Client] Timeout waiting for server result.")

    except Exception as e:
        logger.exception(f"Exception in audio process: {e}")


async def main():
    await connect()
    await asyncio.sleep(2)
    await publish_state({"state": "start_send"})
    await process()
    await asyncio.sleep(10)


if __name__ == "__main__":
    asyncio.run(main())
