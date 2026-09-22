"use client";

import { useEffect, useRef, useState } from "react";

/**
 * The hero's background: a short clip captured to canvas frames and played back as a
 * boomerang (forward then reverse), a faithful React port of the source design's runtime
 * logic. It degrades gracefully — if frame capture fails (e.g. a CORS-tainted canvas) the
 * plain looping `<video>` stays visible instead.
 *
 * TODO: re-host the clip on a domuvai-owned origin before launch; this URL is the design tool's.
 */
const VIDEO_SRC =
  "https://d8j0ntlcm91z4.cloudfront.net/user_38xzZboKViGWJOttwIXH07lWA1P/hf_20260715_090628_7052d8a6-a094-4341-a4a2-ad58493a67a9.mp4";
const MAX_WIDTH = 720;
const MAX_FRAMES = 72;
const FPS = 30;

export default function HeroVideo() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const video = videoRef.current;
    const canvas = canvasRef.current;
    if (!video || !canvas) return;

    const frames: HTMLCanvasElement[] = [];
    const seenTimes = new Set<number>();
    let done = false;
    let idx = 0;
    let dir = 1;
    let timer: ReturnType<typeof setInterval> | undefined;
    let raf = 0;
    let ctx: CanvasRenderingContext2D | null = null;

    const schedule = (fn: () => void) => {
      const withVfc = video as HTMLVideoElement & {
        requestVideoFrameCallback?: (cb: () => void) => number;
      };
      if (withVfc.requestVideoFrameCallback) withVfc.requestVideoFrameCallback(fn);
      else raf = requestAnimationFrame(fn);
    };

    const draw = () => {
      const frame = frames[idx];
      if (ctx && frame) ctx.drawImage(frame, 0, 0);
    };

    const start = () => {
      const first = frames[0];
      if (!first) return;
      canvas.width = first.width;
      canvas.height = first.height;
      ctx = canvas.getContext("2d");
      draw();
      timer = setInterval(() => {
        const last = frames.length - 1;
        if (idx >= last) dir = -1;
        else if (idx <= 0) dir = 1;
        idx += dir;
        draw();
      }, 1000 / FPS);
    };

    const onEnded = () => {
      if (done) return;
      done = true;
      video.pause();
      if (!frames.length) return;
      setReady(true);
      start();
    };

    const capture = () => {
      if (done) return;
      if (video.readyState >= 2 && video.videoWidth) {
        const t = Math.round(video.currentTime * 1000);
        if (!seenTimes.has(t)) {
          seenTimes.add(t);
          const w = Math.min(MAX_WIDTH, video.videoWidth);
          const h = Math.round((video.videoHeight * w) / video.videoWidth);
          const c = document.createElement("canvas");
          c.width = w;
          c.height = h;
          try {
            c.getContext("2d")?.drawImage(video, 0, 0, w, h);
            frames.push(c);
          } catch {
            /* tainted canvas — leave what we have and fall back to the <video> */
          }
        }
        if (frames.length >= MAX_FRAMES) {
          onEnded();
          return;
        }
      }
      schedule(capture);
    };

    const onError = () => {
      // A CORS failure taints capture; drop the attribute and retry as a plain looping video.
      if (video.getAttribute("crossorigin")) {
        video.removeAttribute("crossorigin");
        video.load();
        video.play().catch(() => {});
      }
    };

    video.addEventListener("ended", onEnded);
    video.addEventListener("error", onError);
    video.play().catch(() => {});
    capture();

    return () => {
      video.removeEventListener("ended", onEnded);
      video.removeEventListener("error", onError);
      if (timer) clearInterval(timer);
      if (raf) cancelAnimationFrame(raf);
    };
  }, []);

  const media: React.CSSProperties = {
    width: "100%",
    height: "100%",
    objectFit: "cover",
    objectPosition: "top",
  };

  return (
    <div
      style={{
        position: "absolute",
        inset: 0,
        zIndex: 0,
        transform: "scale(1.15)",
        transformOrigin: "top",
        overflow: "hidden",
        background: "#DEDDD9",
      }}
    >
      <video
        ref={videoRef}
        src={VIDEO_SRC}
        muted
        playsInline
        preload="auto"
        loop
        crossOrigin="anonymous"
        style={{ ...media, display: ready ? "none" : "block" }}
      />
      <canvas ref={canvasRef} style={{ ...media, display: ready ? "block" : "none" }} />
    </div>
  );
}
