
import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';

export interface FloatingOverlayPluginInterface {
  checkPermission(): Promise<{ granted: boolean }>;
  requestPermission(): Promise<{ requested: boolean }>;
  startOverlay(): Promise<void>;
  stopOverlay(): Promise<void>;
  updateInfo(options: {
    pattern: number;
    multiplier: number;
    isPlaying: boolean;
  }): Promise<void>;
  addListener(
    eventName: 'spinNumberSubmitted',
    listenerFunc: (data: { number: number }) => void
  ): Promise<PluginListenerHandle>;
}

export const FloatingOverlay = registerPlugin<FloatingOverlayPluginInterface>('FloatingOverlay');

const QUEUE_KEY = 'magic_spin_pending_overlay_numbers_v1';

export function isAndroidNative(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';
}

export function readQueuedOverlayNumbers(): number[] {
  try {
    const raw = localStorage.getItem(QUEUE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((n) => typeof n === 'number' && Number.isInteger(n) && n >= 0 && n <= 36);
  } catch {
    return [];
  }
}

export function enqueueOverlayNumber(num: number): void {
  if (!Number.isInteger(num) || num < 0 || num > 36) return;
  try {
    const list = readQueuedOverlayNumbers();
    list.push(num);
    localStorage.setItem(QUEUE_KEY, JSON.stringify(list));
    window.dispatchEvent(new CustomEvent('magic-spin-overlay-number', { detail: { number: num } }));
  } catch {
    // Ignore storage errors
  }
}

export function clearQueuedOverlayNumbers(): void {
  try {
    localStorage.removeItem(QUEUE_KEY);
  } catch {
    // Ignore
  }
}
