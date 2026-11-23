export default function RouletteArt() {
  const numbers = [0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26];
  const reds = new Set([1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36]);

  return (
    <div className="relative h-40 sm:h-48 bg-gradient-to-br from-[#172541] via-[#0d1526] to-[#090d18] overflow-hidden">
      {/* glow orbs */}
      <div className="absolute -top-16 -right-10 w-56 h-56 rounded-full bg-[#f6c85f]/15 blur-3xl" />
      <div className="absolute -bottom-20 -left-10 w-56 h-56 rounded-full bg-[#55c7ff]/10 blur-3xl" />

      {/* wheel */}
      <svg viewBox="0 0 200 200" className="absolute -right-8 -top-6 w-52 h-52 sm:w-64 sm:h-64 opacity-90 magic-spin-slow" aria-hidden="true">
        <circle cx="100" cy="100" r="96" fill="#0a1120" stroke="#f6c85f" strokeWidth="2.5" />
        <circle cx="100" cy="100" r="78" fill="none" stroke="#2b3b5b" strokeWidth="1" />
        {numbers.map((n, i) => {
          const a0 = (i / 37) * Math.PI * 2 - Math.PI / 2;
          const a1 = ((i + 1) / 37) * Math.PI * 2 - Math.PI / 2;
          const x0 = 100 + 94 * Math.cos(a0);
          const y0 = 100 + 94 * Math.sin(a0);
          const x1 = 100 + 94 * Math.cos(a1);
          const y1 = 100 + 94 * Math.sin(a1);
          const color = n === 0 ? '#15803d' : reds.has(n) ? '#b91c3c' : '#111a2b';
          return (
            <path
              key={n}
              d={`M100 100 L${x0} ${y0} A94 94 0 0 1 ${x1} ${y1} Z`}
              fill={color}
              stroke="#f6c85f55"
              strokeWidth="0.6"
            />
          );
        })}
        <circle cx="100" cy="100" r="30" fill="#101827" stroke="#f6c85f" strokeWidth="2" />
        <circle cx="100" cy="100" r="12" fill="#f6c85f" />
        <circle cx="100" cy="100" r="5" fill="#0a1120" />
      </svg>

      {/* card shine */}
      <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-[#ffdf91]/60 to-transparent" />
    </div>
  );
}
