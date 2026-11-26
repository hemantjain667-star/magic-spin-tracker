import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';

export type SirenType = 'none' | '8x' | '32x';

export interface SyncStateOptions {
  pattern: number;
  multiplier: number;
  isPlaying: boolean;
  totalUnit: number;
  hits: number;
  misses: number;
  lastSpin: number;
  spinsJson: string;
  patternsJson: string;
  targetProfit: number;
  stopLoss: number;
}

export interface FloatingOverlayPluginInterface {
  checkPermission(): Promise<{ granted: boolean }>;
  requestPermission(): Promise<{ requested: boolean }>;
  startOverlay(): Promise<void>;
  stopOverlay(): Promise<void>;
  startScreenCapture(): Promise<{ started: boolean }>;
  stopScreenCapture(): Promise<void>;
  toggleTargetBox(): Promise<{ visible: boolean }>;
  drainPendingSpins(): Promise<{ spins: number[] }>;
  drainPendingActions(): Promise<{ spins: number[]; undoCount: number }>;
  syncState(options: SyncStateOptions): Promise<void>;
  updateInfo(options: {
    pattern: number;
    multiplier: number;
    isPlaying: boolean;
  }): Promise<void>;
  startSiren(): Promise<void>;
  startSiren32x(): Promise<void>;
  stopSiren(): Promise<void>;
  getSirenState(): Promise<{ playing: boolean; type: SirenType }>;
  addListener(
    eventName: 'spinNumberSubmitted',
    listenerFunc: (data: { number: number; source?: string }) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'undoRequested',
    listenerFunc: () => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'captureStatusChanged',
    listenerFunc: (data: { capturing: boolean; scanning: boolean }) => void
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'sirenStatusChanged',
    listenerFunc: (data: { playing: boolean; type?: SirenType }) => void
  ): Promise<PluginListenerHandle>;
}

export const FloatingOverlay = registerPlugin<FloatingOverlayPluginInterface>('FloatingOverlay');

export function isAndroidNative(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';
}
