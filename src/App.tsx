import { useEffect, useState } from 'react';
import { supabase } from './lib/supabase';
import { calculateRecords } from './lib/game';
import { playMelody, setSoundEnabled } from './lib/sound';
import { getStoredPin } from './components/LockScreen';
import { vibrate } from './lib/haptics';
import Dashboard from './components/Dashboard';
import PinWidget from './components/PinWidget';
import LockScreen from './components/LockScreen';

export default function App() {
  const [spins, setSpins] = useState<number[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [soundOn, setSoundOn] = useState(true);
  const [pinned, setPinned] = useState(false);
  const [unlocked, setUnlocked] = useState(!getStoredPin());

  useEffect(() => {
    supabase
      .from('spins')
      .select('spin_number, value')
      .order('spin_number', { ascending: true })
      .then(({ data, error }) => {
        if (error) {
          setLoadError('History load nahi hui. Internet check karke page reload karein.');
          setLoading(false);
          return;
        }
        setSpins((data ?? []).map((r: { value: number }) => r.value));
        setLoading(false);
      });
  }, []);

  const addSpin = async (num: number) => {
    if (Number.isNaN(num) || num < 0 || num > 36) {
      alert('0 se 36 ke beech number dalein.');
      return;
    }
    const nextNumber = spins.length + 1;
    const { error } = await supabase.from('spins').insert({ spin_number: nextNumber, value: num });
    if (error) {
      alert('Spin save nahi hui, dobara try karein.');
      return;
    }
    setSpins((prev) => [...prev, num]);
    const state = calculateRecords([...spins, num]);
    const last = state.records[state.records.length - 1];
    if (last) {
      if (soundOn) playMelody(last.statusType);
      vibrate(last.statusType === 'HIT' ? [60, 50, 120] : 40);
    }
  };

  const undoSpin = async () => {
    if (!spins.length) return;
    const lastNumber = spins.length;
    const { error } = await supabase.from('spins').delete().eq('spin_number', lastNumber);
    if (error) {
      alert('Undo fail hua, dobara try karein.');
      return;
    }
    setSpins((prev) => prev.slice(0, -1));
  };

  const resetAll = async () => {
    if (!confirm('Kya aap poori spin history reset karna chahte hain?')) return;
    const { error } = await supabase.from('spins').delete().neq('spin_number', 0);
    if (error) {
      alert('Reset fail hua, dobara try karein.');
      return;
    }
    setSpins([]);
  };

  if (!unlocked) {
    return <LockScreen onUnlock={() => setUnlocked(true)} />;
  }

  if (loading) {
    return (
      <div className="min-h-screen bg-[#090d18] flex items-center justify-center">
        <div className="animate-pulse text-[#f6c85f] font-bold tracking-widest text-sm">MAGIC LOADING...</div>
      </div>
    );
  }

  if (loadError) {
    return (
      <div className="min-h-screen bg-[#090d18] flex items-center justify-center p-6">
        <div className="bg-[#111a2b] border border-[#2b3b5b] rounded-2xl p-6 max-w-sm text-center">
          <p className="text-[#ff6474] font-bold mb-2">Connection problem</p>
          <p className="text-[#9baac2] text-sm">{loadError}</p>
        </div>
      </div>
    );
  }

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
