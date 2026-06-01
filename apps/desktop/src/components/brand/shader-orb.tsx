import { useEffect, useRef } from "react";

interface Props {
  size?: number;
  speed?: number;
  intensity?: number;
  hueShift?: number;
  className?: string;
  style?: React.CSSProperties;
}

const VERT = `
  attribute vec2 a_pos;
  void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }
`;

const FRAG = `
  precision mediump float;
  uniform float u_time;
  uniform vec2  u_res;
  uniform float u_intensity;
  uniform float u_hue;

  vec2 hash2(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return -1.0 + 2.0 * fract(sin(p) * 43758.5453123);
  }
  float noise(vec2 p) {
    const float K1 = 0.366025404;
    const float K2 = 0.211324865;
    vec2 i = floor(p + (p.x + p.y) * K1);
    vec2 a = p - i + (i.x + i.y) * K2;
    float m = step(a.y, a.x);
    vec2 o = vec2(m, 1.0 - m);
    vec2 b = a - o + K2;
    vec2 c = a - 1.0 + 2.0 * K2;
    vec3 h = max(0.5 - vec3(dot(a,a), dot(b,b), dot(c,c)), 0.0);
    vec3 n = h*h*h*h * vec3(dot(a, hash2(i)), dot(b, hash2(i + o)), dot(c, hash2(i + 1.0)));
    return dot(n, vec3(70.0));
  }
  float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) {
      v += a * noise(p);
      p *= 2.0;
      a *= 0.5;
    }
    return v;
  }
  vec3 tint(vec3 c, float h) {
    float s = sin(h), co = cos(h);
    return vec3(
      c.r * co + c.b * s * 0.2,
      c.g + c.r * s * 0.08,
      c.b * co - c.r * s * 0.1
    );
  }

  void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_res) / min(u_res.x, u_res.y);
    float t = u_time * 0.12;

    vec2 q = vec2(fbm(uv + t), fbm(uv - t + 5.2));
    vec2 r = vec2(fbm(uv + q * 1.6 + vec2(1.7, 9.2) + t * 0.4),
                  fbm(uv + q * 1.6 + vec2(8.3, 2.8) + t * 0.3));
    float n = fbm(uv + r);

    float d = length(uv) - 0.08 * n;
    float orb  = smoothstep(0.55, 0.05, d);
    float core = smoothstep(0.42, 0.00, d);
    float rim  = smoothstep(0.58, 0.45, d) * (1.0 - smoothstep(0.55, 0.40, d));

    vec3 deep  = vec3(0.10, 0.28, 0.68);
    vec3 mid   = vec3(0.30, 0.55, 0.95);
    vec3 light = vec3(0.65, 0.85, 1.05);

    vec3 col = mix(deep, mid, n * 0.5 + 0.5);
    col = mix(col, light, core * (n * 0.35 + 0.55));
    col += rim * 0.14;
    float lum = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(lum), col, 1.15);
    col = tint(col, u_hue);

    float a = orb * u_intensity;
    gl_FragColor = vec4(col, a);
  }
`;

export function ShaderOrb({
  size = 80,
  speed = 1,
  intensity = 1,
  hueShift = 0,
  className,
  style,
}: Props) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = size * dpr;
    canvas.height = size * dpr;

    const gl = canvas.getContext("webgl", {
      premultipliedAlpha: false,
      antialias: true,
    });
    if (!gl) return;

    const compile = (type: number, src: string) => {
      const s = gl.createShader(type)!;
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
        console.error("[shader-orb] compile:", gl.getShaderInfoLog(s));
      }
      return s;
    };

    const vs = compile(gl.VERTEX_SHADER, VERT);
    const fs = compile(gl.FRAGMENT_SHADER, FRAG);
    const prog = gl.createProgram()!;
    gl.attachShader(prog, vs);
    gl.attachShader(prog, fs);
    gl.linkProgram(prog);
    gl.useProgram(prog);

    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(
      gl.ARRAY_BUFFER,
      new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]),
      gl.STATIC_DRAW,
    );
    const loc = gl.getAttribLocation(prog, "a_pos");
    gl.enableVertexAttribArray(loc);
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);

    const uTime = gl.getUniformLocation(prog, "u_time");
    const uRes = gl.getUniformLocation(prog, "u_res");
    const uInt = gl.getUniformLocation(prog, "u_intensity");
    const uHue = gl.getUniformLocation(prog, "u_hue");

    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.viewport(0, 0, canvas.width, canvas.height);

    let raf = 0;
    const start = performance.now();
    const frame = () => {
      const t = ((performance.now() - start) / 1000) * speed;
      gl.uniform1f(uTime!, t);
      gl.uniform2f(uRes!, canvas.width, canvas.height);
      gl.uniform1f(uInt!, intensity);
      gl.uniform1f(uHue!, hueShift);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      raf = requestAnimationFrame(frame);
    };
    frame();

    return () => {
      // Sem isso, cada re-run do effect (mudança de props) ou desmontagem
      // vazava program/shaders/buffer no contexto WebGL. Auditoria #50/#51.
      cancelAnimationFrame(raf);
      gl.deleteProgram(prog);
      gl.deleteShader(vs);
      gl.deleteShader(fs);
      gl.deleteBuffer(buf);
    };
  }, [size, speed, intensity, hueShift]);

  return (
    <canvas
      ref={canvasRef}
      className={className}
      style={{
        width: size,
        height: size,
        display: "block",
        ...style,
      }}
    />
  );
}
