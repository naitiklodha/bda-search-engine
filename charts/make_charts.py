"""WP12: every report chart (plan §10.2, C1–C11) from charts/data/, eval/ and logged build numbers.
Usage (from ~/bda/repo): ~/bda/venv/bin/python charts/make_charts.py      -> charts/C*.png
A chart whose data file doesn't exist yet is skipped with a note.

Style: validated categorical slots in fixed order (blue, orange, aqua: dataviz palette, CVD-checked),
one y-axis per panel (measures of different units go in separate panels, never a dual axis), hairline
grid, legend for 2+ series, selective direct labels; aqua (below 3:1 contrast) always carries value labels.
"""
import csv
import math
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

S1, S2, S3 = "#2a78d6", "#eb6834", "#1baf7a"           # categorical slots 1-3
SURF, INK, INK2, MUTED, GRID, BASE = "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7"
plt.rcParams.update({
    "figure.facecolor": SURF, "axes.facecolor": SURF, "savefig.facecolor": SURF,
    "font.family": "sans-serif", "font.size": 10, "text.color": INK, "axes.labelcolor": INK2,
    "axes.edgecolor": BASE, "axes.linewidth": 0.8, "xtick.color": MUTED, "ytick.color": MUTED,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.6, "grid.linestyle": "-",
    "axes.spines.top": False, "axes.spines.right": False, "axes.titlesize": 12, "axes.titleweight": "bold",
    "axes.titlelocation": "left", "legend.frameon": False, "figure.dpi": 150})
OUT = "charts"
made, skipped = [], []


def tsv(path, header=False):
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        return None
    with open(path, encoding="utf-8") as f:
        rows = list(csv.reader(f, delimiter="\t"))
    return rows[1:] if header else rows


def save(fig, name, note=""):
    fig.tight_layout()
    fig.savefig(f"{OUT}/{name}.png", bbox_inches="tight")
    plt.close(fig)
    made.append(name + (f" ({note})" if note else ""))


def bars(ax, x, h, color, labels=None, fmt="{:,.0f}", width=0.7, horizontal=False):
    """Thin bars with a 2px surface gap; optional value labels outside the bar end."""
    b = (ax.barh if horizontal else ax.bar)(x, h, width, color=color, edgecolor=SURF, linewidth=2)
    if labels:
        for rect, v in zip(b, h):
            if horizontal:
                ax.annotate(fmt.format(v), (rect.get_width(), rect.get_y() + rect.get_height() / 2),
                            xytext=(4, 0), textcoords="offset points", va="center", fontsize=8, color=INK2)
            else:
                ax.annotate(fmt.format(v), (rect.get_x() + rect.get_width() / 2, rect.get_height()),
                            xytext=(0, 3), textcoords="offset points", ha="center", fontsize=8, color=INK2)
    return b


# ---- C1 Zipf rank-frequency, log-log, fitted slope (ranks <= 10,000)
z = tsv("charts/data/wiki_zipf.tsv")
if z:
    fig, ax = plt.subplots(figsize=(6.4, 4.2))
    for path, label, color in [("charts/data/wiki_zipf.tsv", "Simple English Wikipedia", S1),
                               ("charts/data/msmarco_zipf.tsv", "MS MARCO passages", S2)]:
        d = tsv(path)
        if not d:
            continue
        r = np.array([float(x[0]) for x in d]); cf = np.array([float(x[1]) for x in d])
        m = r <= 10000
        slope, icpt = np.polyfit(np.log10(r[m]), np.log10(cf[m]), 1)
        ax.loglog(r, cf, color=color, lw=2, label=f"{label} (fitted slope {slope:.2f})")
        ax.loglog(r[m], 10 ** (icpt + slope * np.log10(r[m])), color=INK2, lw=1, ls=(0, (1, 2)))
    ax.set(xlabel="Rank of term (by collection frequency)", ylabel="Collection frequency (cf)",
           title="C1  Zipf's law: a few terms dominate")
    ax.legend(loc="lower left", fontsize=8)
    save(fig, "C1_zipf")
else:
    skipped.append("C1")

# ---- C2 Document-length histograms (tokens), one panel per corpus
msm = "charts/data/msmarco_doclen_fine.tsv" if tsv("charts/data/msmarco_doclen_fine.tsv") else "charts/data/msmarco_doclen_hist.tsv"
panels = [(p, t, c) for p, t, c in [("charts/data/wiki_doclen_hist.tsv", "Simple English Wikipedia", S1),
                                     (msm, "MS MARCO passages", S2)] if tsv(p)]
if panels:
    fig, axes = plt.subplots(1, len(panels), figsize=(6.4 * len(panels) / 1.4, 3.8), squeeze=False)
    for ax, (p, title, color) in zip(axes[0], panels):
        d = sorted((int(x[0]), int(x[1])) for x in tsv(p))
        width = 5 if "fine" in p else 50
        cut = 1000 if "wiki" in p else 200
        xs = [b for b, _ in d if b < cut]; ys = [n for b, n in d if b < cut]
        ax.bar(xs, ys, width=width * 0.9, color=color, edgecolor=SURF, linewidth=0.5, align="edge")
        ax.set(xlabel=f"Document length (tokens, bins of {width})", ylabel="Documents", title=title)
        ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{v:,.0f}"))
    fig.suptitle("C2  Document lengths after tokenising", x=0.01, ha="left", fontweight="bold")
    save(fig, "C2_doclen")
else:
    skipped.append("C2")

# ---- C3 Combiner experiment: records (left) and bytes (right), ON vs OFF
d = tsv("charts/data/combiner.tsv", header=True)
if d:
    v = {r[0]: (float(r[1]), float(r[2])) for r in d}
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(9, 3.8))
    for ax, keys, unit, div in [(a1, ["map_output_records", "reduce_input_records", "spilled_records"], "Records (millions)", 1e6),
                                (a2, ["map_output_bytes", "reduce_shuffle_bytes"], "Bytes (MB)", 1e6)]:
        x = np.arange(len(keys)); w = 0.36
        bars(ax, x - w / 2, [v[k][0] / div for k in keys], S1, labels=True, fmt="{:,.1f}", width=w)
        bars(ax, x + w / 2, [v[k][1] / div for k in keys], S2, labels=True, fmt="{:,.1f}", width=w)
        ax.set_xticks(x, [k.replace("_", " ") for k in keys], fontsize=8)
        ax.set_ylabel(unit)
    a1.set_title("C3  Combiner: 40% fewer records, 39.7% less shuffle")
    a1.legend(["combiner on", "combiner off"], loc="upper left", fontsize=8)
    a1.set_ylim(0, max(v["spilled_records"]) / 1e6 * 1.15)
    save(fig, "C3_combiner")
else:
    skipped.append("C3")

# ---- C4 Job 0: bz2 split vs single split (wall time; mapper count in the label)
d = tsv("charts/data/job0_split.tsv", header=True)
if d:
    fig, ax = plt.subplots(figsize=(5.2, 3.4))
    labels = [f"{r[0]}\n{r[1]} mapper{'s' if r[1] != '1' else ''}" for r in d]
    bars(ax, labels, [float(r[2]) for r in d], S1, labels=True, fmt="{:,.0f} s", width=0.5)
    ax.set(ylabel="Wall time (s)", title="C4  Job 0: splitting the bz2 dump (1.8× faster)")
    save(fig, "C4_job0_split")
else:
    skipped.append("C4")

# ---- C5 MRR@10 / P@10 / R@100 by model
d = tsv("charts/data/eval_metrics.tsv")
if d:
    fig, ax = plt.subplots(figsize=(7.6, 4))
    names = ["BM25\n(0.9, 0.4)", "BM25\n(1.2, 0.75)", "TF-IDF", "Boolean\nAND"][:len(d)]
    x = np.arange(len(d)); w = 0.26
    for i, (col, label, color) in enumerate([(2, "MRR@10", S1), (4, "P@10", S2), (5, "R@100", S3)]):
        bars(ax, x + (i - 1) * w, [float(r[col]) for r in d], color, labels=True, fmt="{:.3f}", width=w)
    ax.set_xticks(x, names)
    ax.set(ylabel="Score (0–1)", title="C5  MS MARCO, 1,000 dev/small queries: BM25 > TF-IDF > Boolean")
    ax.legend(["MRR@10", "P@10", "R@100"], loc="upper right", fontsize=8)
    save(fig, "C5_eval_models")
else:
    skipped.append("C5")

# ---- C6 Per-query reciprocal rank, BM25 vs TF-IDF
if os.path.exists("eval/per_query_rr.csv"):
    with open("eval/per_query_rr.csv") as f:
        rows = list(csv.DictReader(f))
    b = np.array([float(r["bm25"]) for r in rows]); t = np.array([float(r["tfidf"]) for r in rows])
    fig, ax = plt.subplots(figsize=(5, 5))
    jit = np.random.default_rng(42).uniform(-0.012, 0.012, (2, len(b)))
    ax.scatter(t + jit[0], b + jit[1], s=12, color=S1, alpha=0.45, edgecolors="none")
    ax.plot([0, 1], [0, 1], color=MUTED, lw=1)
    ax.set(xlim=(-0.03, 1.03), ylim=(-0.03, 1.03), xlabel="TF-IDF reciprocal rank", ylabel="BM25 reciprocal rank",
           title="C6  Per-query RR (points above the line: BM25 better)")
    ax.text(0.98, 0.05, f"BM25 better: {int((b > t).sum())}\nTF-IDF better: {int((t > b).sum())}\ntied: {int((b == t).sum())}",
            ha="right", va="bottom", fontsize=8, color=INK2, transform=ax.transAxes)
    save(fig, "C6_rr_scatter", "jittered ±0.012 so ties stay visible")
else:
    skipped.append("C6")

# ---- C7 PageRank convergence: L1 change (log) and time per iteration, two panels (no dual axis)
if os.path.exists("eval/pagerank_log.csv"):
    with open("eval/pagerank_log.csv") as f:
        rows = list(csv.DictReader(f))
    it = [int(r["iteration"]) for r in rows]
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(9, 3.6))
    a1.semilogy(it, [float(r["l1_change"]) for r in rows], color=S1, lw=2, marker="o", ms=5)
    a1.set(xlabel="Iteration", ylabel="L1 change in ranks (log)", title="C7  PageRank convergence")
    bars(a2, it, [float(r["seconds"]) for r in rows], S1, width=0.6)
    a2.set(xlabel="Iteration", ylabel="Seconds", title="Time per iteration (Spark)")
    save(fig, "C7_pagerank_convergence")
else:
    skipped.append("C7")

# ---- C8 PageRank top 15
d = tsv("eval/pagerank_top50.tsv", header=True)
if d:
    top = d[:15][::-1]
    fig, ax = plt.subplots(figsize=(6.4, 4.8))
    bars(ax, [r[2] for r in top], [float(r[3]) * 1000 for r in top], S1, labels=True, fmt="{:.2f}", width=0.6, horizontal=True)
    ax.set(xlabel="PageRank (×10⁻³)", title="C8  Most-linked-to articles (PageRank top 15)")
    ax.grid(axis="y", visible=False)
    save(fig, "C8_pagerank_top15")
else:
    skipped.append("C8")

# ---- C9 K-means cluster sizes, labelled with top terms
d = tsv("charts/data/kmeans_k20_sizes.tsv")
if d:
    d = sorted(d, key=lambda r: int(r[1]))
    fig, ax = plt.subplots(figsize=(7.4, 6))
    bars(ax, [f"{r[2]}" for r in d], [int(r[1]) for r in d], S1, labels=True, fmt="{:,.0f}", width=0.6, horizontal=True)
    ax.set(xlabel="Articles in cluster", title="C9  K-means (k = 20): clusters by their top 3 terms")
    ax.xaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{v:,.0f}"))
    ax.tick_params(axis="y", labelsize=8)
    ax.grid(axis="y", visible=False)
    save(fig, "C9_kmeans_sizes")
else:
    skipped.append("C9")

# ---- C10 Query latency p50/p95 by model and query type
d = tsv("charts/data/latency.tsv")
if d:
    models = ["bm25", "tfidf", "boolean"]; types = ["rare", "common", "multi"]
    fig, axes = plt.subplots(1, 3, figsize=(10, 3.6), sharey=True)
    for ax, m in zip(axes, models):
        rows = {r[1]: r for r in d if r[0] == m}
        x = np.arange(len(types)); w = 0.36
        bars(ax, x - w / 2, [float(rows[t][3]) for t in types], S1, labels=True, fmt="{:.0f}", width=w)
        bars(ax, x + w / 2, [float(rows[t][4]) for t in types], S2, labels=True, fmt="{:.0f}", width=w)
        ax.set_xticks(x, types)
        ax.set_title({"bm25": "BM25", "tfidf": "TF-IDF", "boolean": "Boolean"}[m], fontsize=10)
    axes[0].set_ylabel("Server time per query (ms)")
    axes[0].legend(["p50", "p95"], loc="upper left", fontsize=8)
    fig.suptitle("C10  Query latency on the search server (S4 target: p50 < 300 ms, p95 < 1 s)", x=0.01, ha="left", fontweight="bold")
    save(fig, "C10_latency")
else:
    skipped.append("C10")

# ---- C11 df distribution by order of magnitude
series = [(p, l, c) for p, l, c in [("charts/data/wiki_df_magnitude.tsv", "Simple English Wikipedia", S1),
                                     ("charts/data/msmarco_df_magnitude.tsv", "MS MARCO", S2)] if tsv(p)]
if series:
    fig, ax = plt.subplots(figsize=(6.4, 3.8))
    mags = sorted({int(r[0]) for p, _, _ in series for r in tsv(p)})
    x = np.arange(len(mags)); w = 0.8 / len(series)
    for i, (p, label, color) in enumerate(series):
        m = {int(r[0]): int(r[1]) for r in tsv(p)}
        bars(ax, x + (i - (len(series) - 1) / 2) * w, [m.get(k, 0) for k in mags], color, labels=True, fmt="{:,.0f}", width=w)
    ax.set_yscale("log")
    ax.set_xticks(x, [f"10^{k}–10^{k + 1}" for k in mags], fontsize=8)
    ax.set(xlabel="Document frequency (df) range", ylabel="Number of terms (log)", title="C11  Most terms are rare")
    ax.legend([l for _, l, _ in series], fontsize=8)
    save(fig, "C11_df_magnitude")
else:
    skipped.append("C11")

print("CHARTS made: " + ", ".join(made))
print("CHARTS skipped (data not there yet): " + (", ".join(skipped) or "none"))
