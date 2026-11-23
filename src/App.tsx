import { useEffect, useState } from 'react';
import { calculateRecords } from './lib/game';
import { playMelody, setSoundEnabled } from './lib/sound';
import { vibrate } from './lib/haptics';
import Dashboard from './components/Dashboard';
import PinWidget from './components/PinWidget';

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

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(spins));
    } catch {
      // Ignore storage quota errors
    }
  }, [spins]);

  const addSpin = (num: number) => {
    if (Number.isNaN(num) || num < 0 || num > 36) {
      alert('0 se 36 ke beech number dalein.');
      return;
    }

    const nextSpins = [...spins, num];
    setSpins(nextSpins);

    try {
      const state = calculateRecords(nextSpins);
      const last = state.records[state.records.length - 1];
      if (last) {
        if (soundOn) playMelody(last.statusType);
        vibrate(last.statusType === 'HIT' ? [80, 50, 120] : 40);
      }
    } catch {
      // Audio/haptics safe fallback
    }
  };

  const undoSpin = () => {
    if (!spins.length) return;
    setSpins((prev) => prev.slice(0, -1));
  };

  const resetAll = () => {
    if (!confirm('Kya aap poori spin history reset karna chahte hain?')) return;
    setSpins([]);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Ignore
    }
  };

  if (pinned) {
    return (
      <PinWidget
        spins={spins}
        soundOn={soundOn}
        onAddSpin={addSpin}
        onUnpin={() => setPinned(false)}
      />
    );
  }

  return (
    <Dashboard
      spins={spins}
      soundOn={soundOn}
      onToggleSound={() => {
        setSoundOn((s) => {
          setSoundEnabled(!s);
          return !s;
        });
      }}
      onAddSpin={addSpin}
      onUndo={undoSpin}
      onReset={resetAll}
      onPin={() => setPinned(true)}
    />
  );
}
