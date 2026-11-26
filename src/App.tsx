import { useCallback, useEffect, useRef, useState } from 'react';
import {
  calculateRecords,
  loadPatterns,
  savePatterns,
  type PatternMap,
} from './lib/game';
import {
  playGoalChime,
  playMelody,
  setSoundEnabled,
  startPoliceSiren,
  startWarning32xSiren,
  stopPoliceSiren,
} from './lib/sound';
import { vibrate } from './lib/haptics';
import Dashboard from './components/Dashboard';
import LockScreen, { getStoredPin } from './components/LockScreen';
import PatternEditorModal from './components/PatternEditorModal';
import PinWidget from './components/PinWidget';
import WelcomeModal from './components/WelcomeModal';
import {
  FloatingOverlay,
  isAndroidNative,
  type SirenType,
} from './native/floatingOverlay';

const STORAGE_KEY = 'magic_spin_tracker_spins_v1';
const PIN_LOCK_KEY = 'magic_pin_lock_enabled_v1';
const TARGET_PROFIT_KEY = 'magic_target_profit_v1';
const STOP_LOSS_KEY = 'magic_stop_loss_v1';

function readNumStorage(key: string): number {
  try {
    const v = parseInt(localStorage.getItem(key) || '0', 10);
    return Number.isFinite(v) && v > 0 ? v : 0;
  } catch {
    return 0;
  }
}

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
  const [pinLockEnabled, setPinLockEnabled] = useState<boolean>(() => {
    try {
      return localStorage.getItem(PIN_LOCK_KEY) === '1';
    } catch {
      return false;
    }
  });
  const [unlocked, setUnlocked] = useState<boolean>(() => {
    try {
      return localStorage.getItem(PIN_LOCK_KEY) !== '1';
    } catch {
      return true;
    }
  });
  const [showWelcome, setShowWelcome] = useState<boolean>(true);
  const [targetProfit, setTargetProfit] = useState<number>(() => readNumStorage(TARGET_PROFIT_KEY));
  const [stopLoss, setStopLoss] = useState<number>(() => readNumStorage(STOP_LOSS_KEY));
  const [goalAlert, setGoalAlert] = useState<'profit' | 'stoploss' | null>(null);

  const [soundOn, setSoundOn] = useState(true);
  const [pinned, setPinned] = useState(false);
  const [isCapturing, setIsCapturing] = useState(false);
  const [sirenActive, setSirenActive] = useState(false);
  const [sirenType, setSirenType] = useState<SirenType>('none');
  const [showPatternEditor, setShowPatternEditor] = useState(false);

  const spinsRef = useRef<number[]>(spins);
  spinsRef.current = spins;
  const patternsRef = useRef<PatternMap>(patterns);
  patternsRef.current = patterns;
  const soundOnRef = useRef<boolean>(soundOn);
  soundOnRef.current = soundOn;
  const targetRef = useRef<number>(targetProfit);
  targetRef.current = targetProfit;
  const stopLossRef = useRef<number>(stopLoss);
  stopLossRef.current = stopLoss;
  const goalFiredRef = useRef<'profit' | 'stoploss' | null>(null);
  const drainingRef = useRef<boolean>(false);

  const triggerSiren = useCallback((type: '8x' | '32x') => {
    setSirenActive(true);
    setSirenType(type);
    vibrate(type === '32x' ? [300, 120, 300, 120, 400] : [200, 100, 200, 100, 300]);
    if (isAndroidNative()) {
      if (type === '32x') FloatingOverlay.startSiren32x().catch(() => {});
      else FloatingOverlay.startSiren().catch(() => {});
    } else {
      if (type === '32x') startWarning32xSiren();
      else startPoliceSiren();
    }
  }, []);

  const handleStopSiren = useCallback(() => {
    setSirenActive(false);
    setSirenType('none');
    stopPoliceSiren();
    if (isAndroidNative()) {
      FloatingOverlay.stopSiren().catch(() => {});
    }
  }, []);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(spins));
    } catch {
      // Ignore
    }
  }, [spins]);

  useEffect(() => {
    try {
      localStorage.setItem(TARGET_PROFIT_KEY, String(targetProfit));
      localStorage.setItem(STOP_LOSS_KEY, String(stopLoss));
    } catch {
      // Ignore
    }
  }, [targetProfit, stopLoss]);

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
      targetProfit,
      stopLoss,
    }).catch(() => {});
  }, [spins, patterns, targetProfit, stopLoss]);

  const checkGoals = useCallback((totalUnit: number) => {
    const tp = targetRef.current;
    const sl = stopLossRef.current;
    if (tp > 0 && totalUnit >= tp) {
      if (goalFiredRef.current !== 'profit') {
        goalFiredRef.current = 'profit';
        setGoalAlert('profit');
        if (soundOnRef.current) playGoalChime(true);
        vibrate([120, 80, 120, 80, 200]);
      }
    } else if (sl > 0 && totalUnit <= -sl) {
      if (goalFiredRef.current !== 'stoploss') {
        goalFiredRef.current = 'stoploss';
        setGoalAlert('stoploss');
        if (soundOnRef.current) playGoalChime(false);
        vibrate([250, 100, 250]);
      }
    } else {
      goalFiredRef.current = null;
    }
  }, []);

  const applyPendingChanges = useCallback(
    (nums: number[], undoCount = 0, fromNative = false) => {
      const valid = nums.filter((n) => Number.isInteger(n) && n >= 0 && n <= 36);
      if (!valid.length && undoCount <= 0) return;

      const prevState = calculateRecords(spinsRef.current, patternsRef.current);
      let base = spinsRef.current;
      if (undoCount > 0) {
        base = base.slice(0, Math.max(0, base.length - undoCount));
      }
      const nextSpins = valid.length ? [...base, ...valid] : base;
      spinsRef.current = nextSpins;
      setSpins(nextSpins);

      try {
        const nextState = calculateRecords(nextSpins, patternsRef.current);
        checkGoals(nextState.totalUnit);

        if (valid.length && !fromNative) {
          const entering32xSecond =
            nextState.nextMult === 32 &&
            nextState.nextStepsRem === 1 &&
            !(prevState.nextMult === 32 && prevState.nextStepsRem === 1);
          const entering8x = prevState.nextMult !== 8 && nextState.nextMult === 8;

          if (entering32xSecond) {
            triggerSiren('32x');
          } else if (entering8x) {
            triggerSiren('8x');
          } else {
            const last = nextState.records[nextState.records.length - 1];
            if (last) {
              if (soundOnRef.current) playMelody(last.statusType);
              vibrate(last.statusType === 'HIT' ? [80, 50, 120] : 40);
            }
          }
        }
      } catch {
        // Ignore
      }
    },
    [checkGoals, triggerSiren]
  );

  const applySingleSpin = useCallback(
    (num: number) => {
      if (!Number.isInteger(num) || num < 0 || num > 36) {
        alert('0 se 36 ke beech number dalein.');
        return;
      }
      applyPendingChanges([num], 0, false);
    },
    [applyPendingChanges]
  );

  const syncNativeSirenStatus = useCallback(async () => {
    if (!isAndroidNative()) return;
    try {
      const { playing, type } = await FloatingOverlay.getSirenState();
      setSirenActive(Boolean(playing));
      setSirenType(playing ? type || '8x' : 'none');
    } catch {
      // Ignore
    }
  }, []);

  const drainNativeQueue = useCallback(async () => {
    if (!isAndroidNative() || drainingRef.current) return;
    drainingRef.current = true;
    try {
      const { spins: pending, undoCount } = await FloatingOverlay.drainPendingActions();
      const safeSpins = Array.isArray(pending) ? pending : [];
      const safeUndo = typeof undoCount === 'number' && undoCount > 0 ? undoCount : 0;
      if (safeSpins.length > 0 || safeUndo > 0) {
        applyPendingChanges(safeSpins, safeUndo, true);
      }
    } catch {
      // Ignore
    } finally {
      drainingRef.current = false;
    }
    syncNativeSirenStatus();
  }, [applyPendingChanges, syncNativeSirenStatus]);

  useEffect(() => {
    drainNativeQueue();

    let spinHandle: { remove: () => Promise<void> } | undefined;
    let undoHandle: { remove: () => Promise<void> } | undefined;
    let captureHandle: { remove: () => Promise<void> } | undefined;
    let sirenHandle: { remove: () => Promise<void> } | undefined;

    if (isAndroidNative()) {
      FloatingOverlay.addListener('spinNumberSubmitted', () => drainNativeQueue()).then((h) => {
        spinHandle = h;
      });
      FloatingOverlay.addListener('undoRequested', () => drainNativeQueue()).then((h) => {
        undoHandle = h;
      });
      FloatingOverlay.addListener('captureStatusChanged', ({ capturing }) => {
        setIsCapturing(Boolean(capturing));
        if (capturing) setPinned(true);
      }).then((h) => {
        captureHandle = h;
      });
      FloatingOverlay.addListener('sirenStatusChanged', ({ playing, type }) => {
        setSirenActive(Boolean(playing));
        setSirenType(playing ? type || '8x' : 'none');
      }).then((h) => {
        sirenHandle = h;
      });
    }

    const onResume = () => {
      if (document.visibilityState === 'visible') drainNativeQueue();
    };
    const pollTimer = window.setInterval(() => {
      if (document.visibilityState === 'visible') drainNativeQueue();
    }, 1500);

    document.addEventListener('visibilitychange', onResume);
    window.addEventListener('focus', drainNativeQueue);

    return () => {
      window.clearInterval(pollTimer);
      document.removeEventListener('visibilitychange', onResume);
      window.removeEventListener('focus', drainNativeQueue);
      spinHandle?.remove();
      undoHandle?.remove();
      captureHandle?.remove();
      sirenHandle?.remove();
      stopPoliceSiren();
    };
  }, [drainNativeQueue]);

  const handleTogglePinLock = () => {
    if (!pinLockEnabled) {
      try { localStorage.setItem(PIN_LOCK_KEY, '1'); } catch {}
      setPinLockEnabled(true);
      if (!getStoredPin()) {
        setUnlocked(false);
      } else {
        alert('4-Digit App PIN Lock ON ho gaya hai!');
      }
    } else {
      try { localStorage.setItem(PIN_LOCK_KEY, '0'); } catch {}
      setPinLockEnabled(false);
      alert('App PIN Lock OFF kar diya gaya hai.');
    }
  };

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
    goalFiredRef.current = null;
  };

  const resetAll = () => {
    if (!confirm('Kya aap poori spin history reset karna chahte hain?')) return;
    spinsRef.current = [];
    setSpins([]);
    setGoalAlert(null);
    goalFiredRef.current = null;
    handleStopSiren();
    try { localStorage.removeItem(STORAGE_KEY); } catch {}
  };

  if (pinLockEnabled && !unlocked) {
    return <LockScreen onUnlock={() => setUnlocked(true)} />;
  }

  return (
    <>
      <Dashboard
        spins={spins}
        patterns={patterns}
        soundOn={soundOn}
        isPinned={pinned}
        isCapturing={isCapturing}
        sirenActive={sirenActive}
        sirenType={sirenType}
        pinLockEnabled={pinLockEnabled}
        targetProfit={targetProfit}
        stopLoss={stopLoss}
        goalAlert={goalAlert}
        onToggleSound={() => {
          setSoundOn((s) => {
            const next = !s;
            setSoundEnabled(next);
            return next;
          });
        }}
        onTogglePinLock={handleTogglePinLock}
        onChangeTargetProfit={setTargetProfit}
        onChangeStopLoss={setStopLoss}
        onDismissGoalAlert={() => setGoalAlert(null)}
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

      {showWelcome && <WelcomeModal onClose={() => setShowWelcome(false)} />}

      {pinned && !isAndroidNative() && (
        <PinWidget
          spins={spins}
          patterns={patterns}
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
