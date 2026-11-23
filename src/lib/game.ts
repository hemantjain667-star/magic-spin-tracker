export const PETTAN_PATTERNS: Record<number, number[]> = {
  1: [1, 11, 21, 31, 2, 12, 22, 32, 3, 13, 23, 33, 6, 16, 26, 36, 7, 17, 27, 8, 18, 28],
  2: [2, 12, 22, 32, 3, 13, 23, 33, 4, 14, 24, 34, 7, 17, 27, 8, 18, 28, 9, 19, 29],
  3: [3, 13, 23, 33, 4, 14, 24, 34, 5, 15, 25, 35, 8, 18, 28, 9, 19, 29, 10, 20, 30, 0],
  4: [4, 14, 24, 34, 5, 15, 25, 35, 6, 16, 26, 36, 9, 19, 29, 10, 20, 30, 0, 1, 11, 21, 31],
  5: [5, 15, 25, 35, 6, 16, 26, 36, 7, 17, 27, 10, 20, 30, 0, 1, 11, 21, 31, 2, 12, 22, 32],
  6: [6, 16, 26, 36, 7, 17, 27, 8, 18, 28, 1, 11, 21, 31, 2, 12, 22, 32, 8, 18, 28, 3, 13, 23, 33],
  7: [7, 17, 27, 8, 18, 28, 9, 19, 29, 2, 12, 22, 32, 3, 13, 23, 33, 4, 14, 24, 34],
  8: [8, 18, 28, 9, 19, 29, 10, 20, 30, 0, 3, 13, 23, 33, 4, 14, 24, 34, 5, 15, 25, 35],
  9: [9, 19, 29, 10, 20, 30, 0, 1, 11, 21, 31, 4, 14, 24, 34, 5, 15, 25, 35, 6, 16, 26, 36],
  0: [10, 20, 30, 0, 1, 11, 21, 31, 2, 12, 22, 32, 5, 15, 25, 35, 6, 16, 26, 36, 7, 17, 27],
};

export const ALL_NUMS = Array.from({ length: 37 }, (_, i) => i);

export interface SpinRecord {
  counting: number;
  spin: number;
  pettan: number;
  mult: number;
  isPlaying: boolean;
  isPlayHit: boolean;
  isPlayMiss: boolean;
  betLabel: string;
  statusType: 'HIT' | 'MISS';
  result: string;
  unit: number;
  totalUnit: number;
}

export interface GameState {
  records: SpinRecord[];
  hits: number;
  misses: number;
  totalUnit: number;
  nextPettan: number;
  isNextPlay: boolean;
  nextMult: number;
}

export function getCutPettanNumbers(pettanDigit: number): number[] {
  const inPettan = new Set(PETTAN_PATTERNS[pettanDigit] || []);
  return ALL_NUMS.filter((n) => !inPettan.has(n));
}

export function calculateRecords(spinList: number[]): GameState {
  const records: SpinRecord[] = [];
  let hits = 0;
  let misses = 0;
  let totalUnit = 0;
  let mult = 1;
  let stepsRem = 0;
  const baseCounting = spinList.length ? spinList[0] : 1;

  for (let i = 0; i < spinList.length; i++) {
    const spin = spinList[i];
    const counting = baseCounting + i;
    const pettan = counting % 10;
    const cutNums = getCutPettanNumbers(pettan);
    const cutCount = cutNums.length;
    const isHit = cutNums.includes(spin);
    const curMult = mult;
    const unit = (isHit ? 36 - cutCount : -cutCount) * curMult;
    totalUnit += unit;
    let result = '';

    if (isHit) {
      hits++;
      if (mult === 1) {
        mult = 2;
        stepsRem = 2;
        result = 'HIT → 2X (2 SPINS)';
      } else {
        mult = 1;
        stepsRem = 0;
        result = 'HIT → RESET 1X';
      }
    } else {
      misses++;
      if (mult > 1) {
        stepsRem--;
        if (stepsRem === 0) {
          mult = mult * 2;
          stepsRem = 2;
          result = `MISS → ${mult}X (2 SPINS)`;
        } else {
          result = `MISS (${stepsRem} LEFT @ ${mult}X)`;
        }
      } else {
        result = 'MISS @ 1X';
      }
    }

    records.push({
      counting,
      spin,
      pettan,
      mult: curMult,
      isPlaying: curMult > 1,
      isPlayHit: isHit && curMult > 1,
      isPlayMiss: !isHit && curMult > 1,
      betLabel: `BET ${curMult}x`,
      statusType: isHit ? 'HIT' : 'MISS',
      result,
      unit,
      totalUnit,
    });
  }

  const nextCounting = spinList.length ? baseCounting + spinList.length : 1;
  return {
    records,
    hits,
    misses,
    totalUnit,
    nextPettan: nextCounting % 10,
    isNextPlay: mult > 1,
    nextMult: mult,
  };
}
