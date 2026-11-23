import { useEffect, useRef, useState } from 'react';
import { calculateRecords } from './lib/game';
import { playMelody, setSoundEnabled } from './lib/sound';
import { vibrate } from './lib/haptics';
import Dashboard from './components/Dashboard';
import {
  clearQueuedOverlayNumbers,
  enqueueOverlayNumber,
  FloatingOverlay,
  isAndroidNative,
  readQueuedOverlayNumbers,
} from './native/floatingOverlay';

const STORAGE_KEY = 'magic_spin_tracker_spins_v1';

export default function App() {
  const [spins, setSpins] = useState<number[]>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (!saved) return [];
      const parsed = JSON.parse(saved);
      return Array.isArray(parsed) ? parsed.filter((n) => typeof n === 'number') : [];
    } catch {
      return [];
    }
  });

  const [soundOn, setSoundOn] = useState(true);
  const [pinned, setPinned] = useState(false);

  const spinsRef = useRef<number[]>(spins);
  spinsRef.current = spins;

  const soundOnRef = useRef<boolean>(soundOn);
  soundOnRef.current = soundOn;

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(spins));
    } catch {
      // Ignore storage quota errors
    }
  }, [spins]);

  const applySpinNumber = (num: number) => {
    if (Number.isNaN(num) || num < 0 || num > 36) {
      alert('0 se 36 ke beech number dalein.');
      return;
    }

    const nextSpins = [...spinsRef.current, num];
    spinsRef.current = nextSpins;
    setSpins(nextSpins);

    try {
      const state = calculateRecords(nextSpins);
      const last = state.records[state.records.length - 1];
      if (last) {
        if (soundOnRef.current) playMelody(last.statusType);
        vibrate(last.statusType === 'HIT' ? [80, 50, 120] : 40);
      }
    } catch {
      // Audio/haptics safe fallback
    }
  };

  useEffect(() => {
    const flushQueue = () => {
      const queued = readQueuedOverlayNumbers();
      if (queued.length > 0) {
        clearQueuedOverlayNumbers();
        queued.forEach((n) => applySpinNumber(n));
      }
    };

    flushQueue();

    let listenerHandle: { remove: () => Promise<void> } | undefined;

    if (isAndroidNative()) {
      FloatingOverlay.addListener('spinNumberSubmitted', ({ number }) => {
        enqueueOverlayNumber(number);
        flushQueue();
      }).then((handle) => {
        listenerHandle = handle;
      });
    }

    const onCustomOverlayNumber = () => {
      flushQueue();
    };
    window.addEventListener('magic-spin-overlay-number', onCustomOverlayNumber);

    return () => {
      window.removeEventListener('magic-spin-overlay-number', onCustomOverlayNumber);
      listenerHandle?.remove();
    };
  }, []);

  const handlePinToggle = async () => {
    if (!pinned) {
      if (isAndroidNative()) {
        const { granted } = await FloatingOverlay.checkPermission();
        if (!granted) {
          await FloatingOverlay.requestPermission();
          return;
        }
        await FloatingOverlay.startOverlay();
      }
      setPinned(true);
    } else {
      if (isAndroidNative()) {
        await FloatingOverlay.stopOverlay();
      }
      setPinned(false);
    }
  };

  const undoSpin = () => {
    if (!spinsRef.current.length) return;
    const nextSpins = spinsRef.current.slice(0, -1);
    spinsRef.current = nextSpins;
    setSpins(nextSpins);
  };

  const resetAll = () => {
    if (!confirm('Kya aap poori spin history reset karna chahte hain?')) return;
    spinsRef.current = [];
    setSpins([]);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Ignore
    }
  };

  return (
    <Dashboard
      spins={spins}
      soundOn={soundOn}
      isPinned={pinned}
      onToggleSound={() => {
        setSoundOn((s) => {
          const next = !s;
          setSoundEnabled(next);
          return next;
        });
      }}
      onAddSpin={applySpinNumber}
      onUndo={undoSpin}
      onReset={resetAll}
      onPin={handlePinToggle}
      onUnpin={handlePinToggle}
    />
  );
}
