"""Local, deterministic video fixture and frame-contact evidence; does not touch app data."""
from pathlib import Path
import argparse
import cv2
import numpy as np
from PIL import Image, ImageDraw

parser = argparse.ArgumentParser()
parser.add_argument('command', choices=['generate', 'inspect'])
parser.add_argument('path', type=Path)
args = parser.parse_args()
if args.command == 'generate':
    writer = cv2.VideoWriter(str(args.path), cv2.VideoWriter_fourcc(*'mp4v'), 30, (1280, 720))
    if not writer.isOpened():
        raise RuntimeError('MPEG-4 encoder unavailable')
    y, x = np.indices((720, 1280))
    for frame in range(360):
        shift = max(0, frame - 90) * 3
        cells = ((x + shift) // 48 + y // 48) % 2
        picture = np.where(cells[..., None] == 0, [190, 150, 90], [65, 42, 25]).astype(np.uint8)
        cv2.putText(picture, 'STATIC' if frame < 90 else 'PAN 90 px/s', (40, 70),
                    cv2.FONT_HERSHEY_SIMPLEX, 1.2, (255, 255, 255), 2)
        writer.write(picture)
    writer.release()
    print(args.path)
else:
    reader = cv2.VideoCapture(str(args.path))
    fps = reader.get(cv2.CAP_PROP_FPS)
    frames = []
    means = []
    steps = []
    previous = None
    i = 0
    while True:
        ok, frame = reader.read()
        if not ok:
            break
        h, w = frame.shape[:2]
        # Central glass fixture excluding labels/buttons, plus matched nearby background.
        glass = frame[int(h*.46):int(h*.54), int(w*.42):int(w*.58)]
        means.append(float(glass.mean()))
        if previous is not None:
            steps.append(float(np.abs(glass.astype(np.float32) - previous).mean()))
        previous = glass.astype(np.float32)
        if 2.0 <= i / fps <= 2.5:
            crop = frame[int(h*.40):int(h*.59), int(w*.20):int(w*.8)]
            frames.append((i, cv2.cvtColor(crop, cv2.COLOR_BGR2RGB)))
        i += 1
    reader.release()
    if frames:
        width = 680
        height = int(frames[0][1].shape[0] * width / frames[0][1].shape[1]) + 24
        sheet = Image.new('RGB', (width*2, height*((len(frames)+1)//2)), '#151923')
        draw = ImageDraw.Draw(sheet)
        for j, (index, pixels) in enumerate(frames):
            px, py = j % 2 * width, j // 2 * height
            sheet.paste(Image.fromarray(pixels).resize((width,height-24)), (px,py+24))
            draw.text((px+8,py+4), f'frame {index} / {index/fps:.3f}s', fill='white')
        sheet.save(args.path.with_suffix('.contact.png'))
    delta = np.abs(np.diff(means))
    print({'frames': i, 'fps': fps, 'roi_mean_range': [min(means), max(means)],
           'successive_roi_change_p95': float(np.percentile(delta,95)),
           'successive_roi_change_max': float(delta.max()),
           'pixel_step_p95': float(np.percentile(steps,95)),
           'pixel_step_max': max(steps)})
