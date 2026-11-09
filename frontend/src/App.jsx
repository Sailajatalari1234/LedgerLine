function App() {
  return (
    <div className="min-h-screen bg-[#0a1628] flex items-center justify-center p-6">
      <div className="w-full max-w-md rounded-2xl bg-[#10213d] p-8 shadow-xl border border-white/10">
        <p className="text-sm uppercase tracking-widest text-emerald-400">
          Secure money movement
        </p>
        <h1 className="mt-2 text-3xl font-bold text-white">LedgerLine</h1>
        <p className="mt-3 text-slate-300">
          Tailwind CSS is working if this card is dark navy with emerald accents.
        </p>
        <button
          type="button"
          className="mt-6 rounded-lg bg-emerald-500 px-5 py-2.5 font-semibold text-[#0a1628] hover:bg-emerald-400"
        >
          Test button
        </button>
      </div>
    </div>
  )
}

export default App