#!/usr/bin/env python3
"""Stand-in for the car's DLNA screen, for testing ScreenShare at home.

Serves a MediaRenderer description and AVTransport control on :49152. When the phone sends
SetAVTransportURI + Play, it records the live stream to recordings/<n>.ts.

Control goes over USB:  adb reverse tcp:49152 tcp:49152   (phone's last_tv_ip = 127.0.0.1)
The stream is fetched over Wi-Fi if reachable, else over USB:  adb forward tcp:18080 tcp:8080

  --rate KBPS   read the stream no faster than this (simulates a slow car); 0 = full speed
  --seconds N   stop recording after N seconds
  --boot NAME   put NAME in the control URL and refuse any other with 404, like the car after a
                restart (restart with a new NAME to test that the phone finds the new address)
  --ssdp        answer SSDP searches like the car does. With --port, restarting on a new port
                acts like the car restarting (its displays come back on a new port each time).
"""
import argparse
import html
import json
import os
import re
import socket
import struct
import subprocess
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DESC = (
    '<?xml version="1.0"?>'
    '<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>'
    '<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>'
    '<friendlyName>Mac test display</friendlyName><manufacturer>test</manufacturer><modelName>fake</modelName>'
    '<UDN>uuid:00000000-0000-4000-8000-000000000001</UDN>'
    '<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>'
    '<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/AVTransport/scpd.xml</SCPDURL>'
    '<controlURL>/AVTransport/{boot}control</controlURL><eventSubURL>/AVTransport/event</eventSubURL>'
    '</service></serviceList></device></root>'
)

args = None
state = {"uri": None, "rec": None, "n": 0}
t_start = time.time()


def log(msg):
    print(f"{time.time() - t_start:7.1f}s  {msg}", flush=True)


def open_stream(url):
    u = urllib.parse.urlsplit(url)
    path = u.path + ("?" + u.query if u.query else "")
    routes = [(u.hostname, u.port or 80, "Wi-Fi"), ("127.0.0.1", args.usb_port, "USB")]
    for host, port, via in routes:
        try:
            s = socket.socket()
            # Small receive buffer so a slow reader pushes back on the phone quickly, like a real car.
            s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 64 * 1024)
            s.settimeout(3)
            s.connect((host, port))
            s.sendall(f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nConnection: close\r\n\r\n".encode())
            buf = b""
            while b"\r\n\r\n" not in buf:
                d = s.recv(4096)
                if not d:
                    raise IOError("closed before headers")
                buf += d
            head, rest = buf.split(b"\r\n\r\n", 1)
            status = head.split(b"\r\n")[0].decode()
            log(f"stream via {via} ({host}:{port}): {status}")
            if " 200 " not in status:
                s.close()
                continue
            s.settimeout(10)
            return s, rest, via
        except Exception as e:
            log(f"stream via {via} ({host}:{port}) failed: {e}")
    return None, b"", None


class Recorder(threading.Thread):
    def __init__(self, url, path, rate_kbps, seconds):
        super().__init__(daemon=True)
        self.url, self.path, self.rate_kbps, self.seconds = url, path, rate_kbps, seconds
        self.stop_evt = threading.Event()

    def run(self):
        s, first, via = open_stream(self.url)
        if not s:
            log("could not open the stream")
            return
        rate = self.rate_kbps * 1000 / 8  # kbit/s -> bytes/s
        total, t0, last_report = 0, time.time(), time.time()
        # Show what this "car" receives in a window on the Mac.
        viewer = None
        if args.watch:
            speed = "full speed" if not self.rate_kbps else f"slow car, {self.rate_kbps / 1000:.1f} Mbps"
            try:
                viewer = subprocess.Popen(
                    ["ffplay", "-hide_banner", "-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay",
                     "-framedrop", "-autoexit", "-x", "370", "-y", "800",
                     "-window_title", f"Car screen stand-in: {speed}", "-f", "mpegts", "-i", "pipe:0"],
                    stdin=subprocess.PIPE)
            except Exception as e:
                log(f"viewer failed to open: {e}")

        def feed(b):
            nonlocal viewer
            if viewer:
                try:
                    viewer.stdin.write(b)
                    viewer.stdin.flush()
                except Exception:
                    viewer = None  # window closed

        with open(self.path, "wb") as f:
            f.write(first)
            feed(first)
            total += len(first)
            while not self.stop_evt.is_set() and time.time() - t0 < self.seconds:
                try:
                    chunk = s.recv(16 * 1024)
                except socket.timeout:
                    log("no data for 10 s")
                    break
                if not chunk:
                    log("phone closed the stream")
                    break
                f.write(chunk)
                feed(chunk)
                total += len(chunk)
                if rate:
                    ahead = total / rate - (time.time() - t0)
                    if ahead > 0:
                        time.sleep(ahead)
                if time.time() - last_report >= 2:
                    el = time.time() - t0
                    log(f"  {total / 1024:8.0f} KB in {el:4.1f}s  ({total * 8 / el / 1e6:.2f} Mbps)")
                    last_report = time.time()
        s.close()
        if viewer:
            try:
                viewer.stdin.close()
            except Exception:
                pass
        el = time.time() - t0
        log(f"recorded {total / 1024:.0f} KB in {el:.1f}s via {via} -> {self.path}")
        with open(self.path + ".json", "w") as j:
            json.dump({"wall_seconds": el, "bytes": total, "via": via, "rate_kbps": self.rate_kbps}, j)
        log("RECORDING DONE")


def start_recording(rate_kbps=None, seconds=None):
    stop_recording()
    if not state["uri"]:
        return False
    state["n"] += 1
    rate_kbps = args.rate if rate_kbps is None else rate_kbps
    path = os.path.join(args.out, f"{state['n']}.ts")
    state["rec"] = Recorder(state["uri"], path, rate_kbps, seconds or args.seconds)
    state["rec"].start()
    log(f"recording {state['uri']} -> {path} at {'full speed' if not rate_kbps else f'{rate_kbps:.0f} kbit/s'}")
    return True


def stop_recording():
    r = state["rec"]
    if r and r.is_alive():
        r.stop_evt.set()
        r.join(3)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def reply(self, code, body=b"", ctype='text/xml; charset="utf-8"'):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/record"):
            # Test hook: record the current stream again, e.g. /record?rate=1200&seconds=20
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            ok = start_recording(float(q.get("rate", ["0"])[0]), float(q.get("seconds", ["0"])[0]) or None)
            self.reply(200 if ok else 409, b"ok" if ok else b"no stream yet", "text/plain")
        elif self.path.startswith("/description.xml"):
            log("phone fetched the description")
            self.reply(200, DESC.replace("{boot}", f"{args.boot}/" if args.boot else "").encode())
        else:
            self.reply(404)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8", "replace")
        expected = f"/AVTransport/{args.boot}/control" if args.boot else "/AVTransport/control"
        if urllib.parse.urlsplit(self.path).path != expected:
            log(f"404 for old control URL {self.path}")
            self.reply(404)
            return
        action = (self.headers.get("SOAPACTION") or "").strip().strip('"').split("#")[-1]
        log(f"SOAP {action}")
        extra = ""
        if action == "SetAVTransportURI":
            m = re.search(r"<CurrentURI>(.*?)</CurrentURI>", body, re.S)
            state["uri"] = html.unescape(m.group(1)) if m else None
            meta = re.search(r"protocolInfo=&quot;([^&]*)&quot;", body)
            log(f"  uri={state['uri']}  protocolInfo={meta.group(1) if meta else '(none)'}")
        elif action == "Play":
            start_recording()
        elif action == "Stop":
            stop_recording()
        elif action == "GetPositionInfo":
            extra = "<Track>1</Track><TrackDuration>00:00:00</TrackDuration><RelTime>00:00:00</RelTime>"
        resp = (
            '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
            f'<u:{action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">{extra}'
            f"</u:{action}Response></s:Body></s:Envelope>"
        )
        self.reply(200, resp.encode())


def ssdp_responder():
    """Answers M-SEARCH on 239.255.255.250:1900 with this display's description URL."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, "SO_REUSEPORT"):
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    s.bind(("", 1900))
    mreq = struct.pack("4s4s", socket.inet_aton("239.255.255.250"), socket.inet_aton("0.0.0.0"))
    s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    while True:
        data, addr = s.recvfrom(4096)
        if not data.startswith(b"M-SEARCH"):
            continue
        # Our address on the way back to the phone.
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(addr)
        my_ip = probe.getsockname()[0]
        probe.close()
        resp = (
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n"
            f"LOCATION: http://{my_ip}:{args.port}/description.xml\r\n"
            "SERVER: fake/1.0 UPnP/1.0\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
            "USN: uuid:00000000-0000-4000-8000-000000000001::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        )
        s.sendto(resp.encode(), addr)
        log(f"SSDP: answered {addr[0]} with port {args.port}")


def main():
    global args
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=49152)
    p.add_argument("--usb-port", type=int, default=18080)
    p.add_argument("--rate", type=float, default=0, help="max read speed in kbit/s, 0 = unlimited")
    p.add_argument("--seconds", type=float, default=20)
    p.add_argument("--no-watch", dest="watch", action="store_false", help="don't open a viewer window")
    p.add_argument("--ssdp", action="store_true", help="answer SSDP searches")
    p.add_argument("--boot", default="", help="name in the control URL; others get 404")
    p.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "recordings"))
    args = p.parse_args()
    os.makedirs(args.out, exist_ok=True)
    state["n"] = len([f for f in os.listdir(args.out) if f.endswith(".ts")])
    log(f"fake display on :{args.port}, rate={'unlimited' if not args.rate else f'{args.rate:.0f} kbit/s'}, "
        f"{args.seconds:.0f}s per recording, saving to {args.out}")
    if args.ssdp:
        threading.Thread(target=ssdp_responder, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
