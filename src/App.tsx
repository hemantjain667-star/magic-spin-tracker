import { useCallback, useEffect, useRef, useState } from 'react';
import {
  calculateRecords,
  loadPatterns,
  savePatterns,
  type PatternMap,
} from './lib/game';
import {
  playMelody,
  setSoundEnabled,
  startPoliceSiren,
  stopPoliceSiren,
} from './lib/sound';
import { vibrate } from './lib/haptics';
import Dashboard from './components/Dashboard';
import PatternEditorModal from './components/PatternEditorModal';
import PinWidget from './components/PinWidget';
import { FloatingOverlay, isAndroidNative } from './native/floatingOverlay';

const STORAGE_KEY = 'magic_spin_tracker_spins_v1';

export default function App() {
  const [spins, setSpins] = useState<number[]>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (!saved) return [];
      const parsed = JSON.parse(saved);
      return Array.isArray(parsed)
        ? parsed.filter((n) => typeof n === 'number' && Number.isInteger(n) && n >= 0 && n <= 36)
        : [];
    } catch {
      return [];
    }
  });

  const [patterns, setPatterns] = useState<PatternMap>(() => loadPatterns());
  const [soundOn, setSoundOn] = useState(true);
  const [pinned, setPinned] = useState(false);
  const [isCapturing, setIsCapturing] = useState(false);
  const [sirenActive, setSirenActive] = useState(false);
  const [showPatternEditor, setShowPatternEditor] = useState(false);

  const spinsRef = useRef<number[]>(spins);
  spinsRef.current = spins;

  const patternsRef = useRef<PatternMap>(patterns);
  patternsRef.current = patterns;

  const soundOnRef = useRef<boolean>(soundOn);
  soundOnRef.current = soundOn;

  const drainingRef = useRef<boolean>(false);

  const triggerSiren = useCallback(() => {
    setSirenActive(true);
    vibrate([200, 100, 200, 100, 300]);
    if (isAndroidNative()) {
      FloatingOverlay.startSiren().catch(() => {});
    } else {
      startPoliceSiren();
    }
  }, []);

  const handleStopSiren = useCallback(() => {
    setSirenActive(false);
    stopPoliceSiren();
    if (isAndroidNative()) {
      FloatingOverlay.stopSiren().catch(() => {});
    }
  }, []);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(spins));
    } catch {
      // Ignore quota errors
    }
  }, [spins]);

  useEffect(() => {
    if (!isAndroidNative()) return;

    const state = calculateRecords(spins, patterns);
    FloatingOverlay.syncState({
      pattern: state.nextPettan,
      multiplier: state.nextMult,
      isPlaying: state.isNextPlay,
      totalUnit: state.totalUnit,
      hits: state.hits,
      misses: state.misses,
      lastSpin: spins.length ? spins[spins.length - 1] : -1,
      spinsJson: JSON.stringify(spins),
      patternsJson: JSON.stringify(patterns),
    }).catch(() => {});
  }, [spins, patterns]);

  const applySpinNumbers = useCallback(
    (nums: number[], fromNative = false) => {
      const valid = nums.filter((n) => Number.isInteger(n) && n >= 0 && n <= 36);
      if (!valid.length) return;

      const prevState = calculateRecords(spinsRef.current, patternsRef.current);
      const nextSpins = [...spinsRef.current, ...valid];
      spinsRef.current = nextSpins;
      setSpins(nextSpins);

      try {
        const nextState = calculateRecords(nextSpins, patternsRef.current);
        const last = nextState.records[nextState.records.length - 1];

        if (!fromNative && prevState.nextMult !== 8 && nextState.nextMult === 8) {
          triggerSiren();
        } else if (last) {
          if (soundOnRef.current) playMelody(last.statusType);
          vibrate(last.statusType === 'HIT' ? [80, 50, 120] : 40);
        }
      } catch {
        // Ignore audio/haptics errors
      }
    },
    [triggerSiren]
  );

  const applySingleSpin = useCallback(
    (num: number) => {
      if (!Number.isInteger(num) || num < 0 || num > 36) {
        alert('0 se 36 ke beech number dalein.');
        return;
      }
      applySpinNumbers([num], false);
    },
    [applySpinNumbers]
  );

  const syncNativeSirenStatus = useCallback(async () => {
    if (!isAndroidNative()) return;
    try {
      const { playing } = await FloatingOverlay.getSirenState();
      setSirenActive(Boolean(playing));
    } catch {
      // Ignore
    }
  }, []);

  const drainNativeQueue = useCallback(async () => {
    if (!isAndroidNative() || drainingRef.current) return;
    drainingRef.current = true;
    try {
      const { spins: pending } = await FloatingOverlay.drainPendingSpins();
      if (Array.isArray(pending) && pending.length > 0) {
        applySpinNumbers(pending, true);
      }
    } catch {
      // Ignore
    } finally {
      drainingRef.current = false;
    }
    syncNativeSirenStatus();
  }, [applySpinNumbers, syncNativeSirenStatus]);

  useEffect(() => {
    drainNativeQueue();

    let spinHandle: { remove: () => Promise<void> } | undefined;
    let captureHandle: { remove: () => Promise<void> } | undefined;
    let sirenHandle: { remove: () => Promise<void> } | undefined;

    if (isAndroidNative()) {
      FloatingOverlay.addListener('spinNumberSubmitted', () => {
        drainNativeQueue();
      }).then((h) => {
        spinHandle = h;
      });

      FloatingOverlay.addListener('captureStatusChanged', ({ capturing }) => {
        setIsCapturing(Boolean(capturing));
        if (capturing) setPinned(true);
      }).then((h) => {
        captureHandle = h;
      });

      FloatingOverlay.addListener('sirenStatusChanged', ({ playing }) => {
        setSirenActive(Boolean(playing));
      }).then((h) => {
        sirenHandle = h;
      });
    }

    const onResume = () => {
      if (document.visibilityState === 'visible') {
        drainNativeQueue();
      }
    };

    // Safety poll while app is visible so zero background spins are ever missed
    const pollTimer = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        drainNativeQueue();
      }
    }, 1500);

    document.addEventListener('visibilitychange', onResume);
    window.addEventListener('focus', drainNativeQueue);

    return () => {
      window.clearInterval(pollTimer);
      document.removeEventListener('visibilitychange', onResume);
      window.removeEventListener('focus', drainNativeQueue);
      spinHandle?.remove();
      captureHandle?.remove();
      sirenHandle?.remove();
      stopPoliceSiren();
    };
  }, [drainNativeQueue]);

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
        setIsCapturing(false);
      }
      setPinned(false);
    }
  };

  const handleToggleCapture = async () => {
    if (!isAndroidNative()) {
      alert('Auto Screen Capture sirf Android APK mein kaam karta hai.');
      return;
    }

    if (!isCapturing) {
      const { granted } = await FloatingOverlay.checkPermission();
      if (!granted) {
        alert('Pehle "Display over other apps" permission allow karein.');
        await FloatingOverlay.requestPermission();
        return;
      }
      const res = await FloatingOverlay.startScreenCapture();
      if (res?.started) {
        setIsCapturing(true);
        setPinned(true);
      }
    } else {
      await FloatingOverlay.stopScreenCapture();
      setIsCapturing(false);
    }
  };

  const handleToggleTargetBox = async () => {
    if (!isAndroidNative()) return;
    await FloatingOverlay.toggleTargetBox();
  };

  const handleSavePatterns = (next: PatternMap) => {
    savePatterns(next);
    setPatterns(next);
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
    handleStopSiren();
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Ignore
    }
  };

  return (
    <>
      <Dashboard
        spins={spins}
        patterns={patterns}
        soundOn={soundOn}
        isPinned={pinned}
        isCapturing={isCapturing}
        sirenActive={sirenActive}
        onToggleSound={() => {
          setSoundOn((s) => {
            const next = !s;
            setSoundEnabled(next);
            return next;
          });
        }}
        onAddSpin={applySingleSpin}
        onUndo={undoSpin}
        onReset={resetAll}
        onPin={handlePinToggle}
        onUnpin={handlePinToggle}
        onToggleCapture={handleToggleCapture}
        onToggleTargetBox={handleToggleTargetBox}
        onOpenPatternEditor={() => setShowPatternEditor(true)}
        onStopSiren={handleStopSiren}
      />

      {pinned && !isAndroidNative() && (
        <PinWidget
          spins={spins}
          soundOn={soundOn}
          onAddSpin={applySingleSpin}
          onUnpin={handlePinToggle}
        />
      )}

      {showPatternEditor && (
        <PatternEditorModal
          patterns={patterns}
          onSave={handleSavePatterns}
          onClose={() => setShowPatternEditor(false)}
        />
      )}
    </>
  );
}
