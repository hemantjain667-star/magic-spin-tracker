let enabled = true;

export function setVibrationEnabled(on: boolean) {
  enabled = on;
}

export function vibrate(pattern: number | number[]) {
  if (!enabled) return;
  try {
    if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
      navigator.vibrate(pattern);
    }
  } catch {
    /* vibration unsupported */
  }
}
