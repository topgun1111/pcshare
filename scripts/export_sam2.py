"""EdgeTAM (default) or SAM 2.1 -> ONNX files for the Android `sam` build (CI only; Apache-2.0 code).

Usage: python scripts/export_sam2.py OUT_DIR [edgetam|base_plus|small|large] [--no-memory] [--no-check]
Needs: pip install torch torchvision onnx onnxruntime + ONE of (both install a package named `sam2`, never together):
  edgetam:  git+https://github.com/facebookresearch/EdgeTAM.git
  others:   git+https://github.com/facebookresearch/sam2.git

Every graph has STATIC shapes (the Hexagon HTP needs them) and float32 I/O (the app runs them fp16 on the HTP).
  sam-encoder.onnx      image [1,3,1024,1024] (RGB, ImageNet mean/std)
                        -> high_res_feats_0 [1,32,256,256], high_res_feats_1 [1,64,128,128],
                           image_embed [1,256,64,64] (raw + no_mem_embed: first frame, no memory),
                           image_feat  [1,256,64,64] (raw: input of the two memory graphs)
  sam-decoder.onnx      image_embed, high_res_feats_0/1, point_coords [1,3,2] (1024 px), point_labels [1,3]
                        (1 pos, 0 neg, 2/3 box corners, -1 = unused slot)  -> multimask (3 candidates):
                        low_res_masks [1,3,256,256] (logits), iou_predictions [1,3], obj_ptrs [1,3,256], object_score [1,1]
  sam-decoder-trk.onnx  same inputs with ONE point (label -1 = no prompt) -> the single mask SAM 2 uses on memory frames:
                        low_res_masks [1,1,256,256], iou_predictions [1,1], obj_ptrs [1,1,256], object_score [1,1]
  sam-memenc.onnx       image_feat, low_res_mask [1,1,256,256], object_score [1,1] -> mem_feat [1, ...]
  sam-memattn.onnx      image_feat, mem_feats [K, ...], ptrs [P,256] -> image_embed [1,256,64,64] (memory-conditioned)
        slot order of mem_feats / ptrs: 0 = prompted (first) frame, d = the frame d steps back (d = 1..K-1 / 1..P-1).
        Temporal position codes are baked in for exactly this layout (frame_idx = P in the traced call).
The memory graphs call the model's OWN `_encode_new_memory` / `_prepare_memory_conditioned_features`, so EdgeTAM's perceiver and
RoPE variants are included as they are. RoPE is rewritten real-valued for ONNX (complex ops do not export); the script checks
that rewrite against the original and every ONNX file against PyTorch, and stops on a mismatch.
"""
from __future__ import annotations

import sys
import urllib.request
from pathlib import Path

_SAM21 = "https://dl.fbaipublicfiles.com/segment_anything_2/092824/sam2.1_hiera_{}.pt"
VARIANTS = {  # name -> (config candidates, checkpoint URL candidates); tried in order (repo layouts differ)
    "edgetam": (["configs/edgetam.yaml", "edgetam.yaml"],
                ["https://huggingface.co/facebook/EdgeTAM/resolve/main/edgetam.pt",
                 "https://huggingface.co/yangchris11/edgetam/resolve/main/edgetam.pt"]),
    "base_plus": (["configs/sam2.1/sam2.1_hiera_b+.yaml"], [_SAM21.format("base_plus")]),
    "small": (["configs/sam2.1/sam2.1_hiera_s.yaml"], [_SAM21.format("small")]),
    "large": (["configs/sam2.1/sam2.1_hiera_l.yaml"], [_SAM21.format("large")]),
}
MIN_BYTES = 5_000_000
S, FM = 1024, 64  # image side, feature map side (S/16)
NO_OBJ = -32.0    # SAM 2 uses -1024 for "no object"; clamped like the logits (fp16-safe, sigmoid ~ 0 either way)


def _download(urls, ckpt: Path) -> bool:
    for u in urls:
        if ckpt.is_file() and ckpt.stat().st_size > 1_000_000:
            break
        try:
            urllib.request.urlretrieve(u, ckpt)
        except Exception as e:  # try the next mirror
            print(f"download failed ({u}): {e}", file=sys.stderr)
    return ckpt.is_file() and ckpt.stat().st_size > 1_000_000


def _patch_rope_real(sam) -> int:
    """Complex RoPE -> real arithmetic (ONNX cannot export complex). Returns the number of patched functions."""
    import torch

    def rope(xq, xk, freqs_cis, repeat_freqs_k=False):  # freqs_cis: real [L, D/2, 2] (see the module pre-pass below)
        def split(x):
            x = x.float().reshape(*x.shape[:-1], -1, 2)
            return x[..., 0], x[..., 1]
        qa, qb = split(xq)
        shape = [d if i >= qa.ndim - 2 else 1 for i, d in enumerate(qa.shape)]
        c, s = freqs_cis[..., 0].reshape(shape), freqs_cis[..., 1].reshape(shape)
        q = torch.stack([qa * c - qb * s, qa * s + qb * c], -1).flatten(3)
        if xk.shape[-2] == 0:
            return q.type_as(xq), xk
        ka, kb = split(xk)
        if repeat_freqs_k:
            r = ka.shape[-2] // qa.shape[-2]
            c, s = c.repeat(*([1] * (c.ndim - 2)), r, 1), s.repeat(*([1] * (s.ndim - 2)), r, 1)  # tiled like the original, not interleaved
        k = torch.stack([ka * c - kb * s, ka * s + kb * c], -1).flatten(3)
        return q.type_as(xq), k.type_as(xk)

    n = 0
    for name, mod in list(sys.modules.items()):
        if name.startswith("sam2") and mod is not None and hasattr(mod, "apply_rotary_enc"):
            mod.apply_rotary_enc = rope
            n += 1
    for m in sam.modules():
        f = getattr(m, "freqs_cis", None)
        if isinstance(f, torch.Tensor) and f.is_complex():
            m.freqs_cis = torch.view_as_real(f).detach()
    return n


def main(argv: list[str]) -> int:
    import inspect

    import numpy as np
    import torch
    import torch.nn.functional as F
    from sam2.build_sam import build_sam2
    from torch import nn

    flags = {a for a in argv[1:] if a.startswith("--")}
    pos = [a for a in argv[1:] if not a.startswith("--")]
    out = Path(pos[0] if pos else "sam-models")
    size = pos[1] if len(pos) > 1 else "edgetam"
    cfgs, urls = VARIANTS[size]
    out.mkdir(parents=True, exist_ok=True)
    ckpt = out / f"{size}.pt"
    if not _download(urls, ckpt):
        print("ERROR: checkpoint download failed", file=sys.stderr)
        return 1
    sam, err = None, None
    for c in cfgs:
        try:
            sam = build_sam2(c, str(ckpt), device="cpu").eval()
            break
        except Exception as e:
            err = e
            print(f"build_sam2({c}) failed: {e}", file=sys.stderr)
    if sam is None:
        raise err
    torch.manual_seed(0)

    # ---------------- wrappers ----------------
    class Encoder(nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, x):
            fpn = self.m.image_encoder(x)["backbone_fpn"]
            f0, f1 = self.m.sam_mask_decoder.conv_s0(fpn[0]), self.m.sam_mask_decoder.conv_s1(fpn[1])
            return f0, f1, fpn[2] + self.m.no_mem_embed.reshape(1, -1, 1, 1), fpn[2]

    class Decoder(nn.Module):
        """Prompt encoder + mask decoder, arithmetic masks only (boolean indexing exports to NonZero, not HTP-friendly)."""

        def __init__(self, m, multimask):
            super().__init__()
            self.m, self.pe, self.md, self.multi = m, m.sam_prompt_encoder, m.sam_mask_decoder, multimask

        def forward(self, image_embed, hr0, hr1, point_coords, point_labels):
            c = point_coords + 0.5
            c = torch.cat([c, torch.zeros_like(c[:, :1])], dim=1)  # SAM 2 always appends one padding point
            lab = torch.cat([point_labels, -torch.ones_like(point_labels[:, :1])], dim=1)
            emb = self.pe.pe_layer._pe_encoding(c / self.m.image_size)
            lab = lab.unsqueeze(-1).expand_as(emb)
            emb = emb * (lab != -1) + self.pe.not_a_point_embed.weight * (lab == -1)
            for i in range(self.pe.num_point_embeddings):
                emb = emb + self.pe.point_embeddings[i].weight * (lab == i)
            dense = self.pe.no_mask_embed.weight.reshape(1, -1, 1, 1).expand(1, -1, FM, FM)
            masks, iou, tokens, score = self.md.predict_masks(
                image_embeddings=image_embed, image_pe=self.pe.get_dense_pe(), sparse_prompt_embeddings=emb,
                dense_prompt_embeddings=dense, repeat_image=False, high_res_features=[hr0, hr1])
            sl = slice(1, None) if self.multi else slice(0, 1)
            masks, iou = masks[:, sl], iou[:, sl]
            if not self.multi:
                tokens = tokens[:, 0:1]
            elif getattr(self.m, "use_multimask_token_for_obj_ptr", True):
                tokens = tokens[:, 1:]
            else:  # SAM 2 then takes the pointer from token 0 for every candidate
                tokens = tokens[:, 0:1].expand(-1, 3, -1)
            appearing = (score > 0).float()                       # [1,1]
            masks = torch.where(appearing[:, :, None, None] > 0, masks, torch.full_like(masks, NO_OBJ))
            ptr = self.m.obj_ptr_proj(tokens)                     # [1,M,256]
            if getattr(self.m, "pred_obj_scores", False):
                lam = (score.sigmoid() if getattr(self.m, "soft_no_obj_ptr", False) else appearing)[:, :, None]
                if getattr(self.m, "fixed_no_obj_ptr", False):
                    ptr = lam * ptr
                ptr = ptr + (1 - lam) * self.m.no_obj_ptr
            return torch.clamp(masks, -32.0, 32.0), iou, ptr, score

    class MemEnc(nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m
            self.pass_score = "object_score_logits" in inspect.signature(m._encode_new_memory).parameters

        def run(self, image_feat, low_res_mask, object_score):
            hr = F.interpolate(low_res_mask, size=(S, S), mode="bilinear", align_corners=False)
            feats = [image_feat.flatten(2).permute(2, 0, 1)]
            args = (feats, [(FM, FM)], hr) + ((object_score,) if self.pass_score else ()) + (False,)
            return self.m._encode_new_memory(*args)

        def forward(self, image_feat, low_res_mask, object_score):
            return self.run(image_feat, low_res_mask, object_score)[0]

    class MemAttn(nn.Module):
        def __init__(self, m, pos_flat, mem_pos, k, p):
            super().__init__()
            self.m, self.k, self.p = m, k, p
            self.register_buffer("pos", pos_flat)
            self.register_buffer("mem_pos", mem_pos)

        def forward(self, image_feat, mem_feats, ptrs):
            out = self._forward(image_feat, mem_feats, ptrs)
            if not getattr(self.m, "use_obj_ptrs_in_encoder", True):
                out = out + 0.0 * ptrs.sum()  # an unused input would be dropped from the ONNX graph and the app's run() would fail
            return out

        def _forward(self, image_feat, mem_feats, ptrs):
            fi = self.p  # cond frame at 0 is exactly P back: inside max_obj_ptrs_in_encoder, so its pointer is used
            cond = {0: {"maskmem_features": mem_feats[0:1], "maskmem_pos_enc": [self.mem_pos], "obj_ptr": ptrs[0:1]}}
            non = {}
            for d in range(1, self.p):
                e = {"obj_ptr": ptrs[d:d + 1]}
                if d < self.k:
                    e.update(maskmem_features=mem_feats[d:d + 1], maskmem_pos_enc=[self.mem_pos])
                non[fi - d] = e
            return self.m._prepare_memory_conditioned_features(
                frame_idx=fi, is_init_cond_frame=False, current_vision_feats=[image_feat.flatten(2).permute(2, 0, 1)],
                current_vision_pos_embeds=[self.pos], feat_sizes=[(FM, FM)],
                output_dict={"cond_frame_outputs": cond, "non_cond_frame_outputs": non}, num_frames=fi + 1,
                track_in_reverse=False)

    def export(model, args, path, inputs, outputs):
        kw = dict(input_names=inputs, output_names=outputs, opset_version=17, do_constant_folding=True)
        try:
            torch.onnx.export(model, args, str(path), dynamo=False, **kw)
        except TypeError:  # older torch has no `dynamo` argument
            torch.onnx.export(model, args, str(path), **kw)
        # torch.onnx.export restores the wrapper's original mode (train, as a fresh nn.Module) on ALL children: BatchNorm would then
        # use batch statistics in the PyTorch reference and every later check would differ. Put everything back in eval.
        model.eval()
        sam.eval()

    def ort_check(path: Path, model, args, names, tol=2e-2) -> None:
        try:
            import onnxruntime as ort
        except ImportError:
            print(f"WARNING: onnxruntime missing, {path.name} not checked", file=sys.stderr)
            return
        model.eval()
        sam.eval()
        sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        got = sess.run(None, {n: a.numpy() for n, a in zip(names, args)})
        ref = model(*args)
        ref = [ref] if isinstance(ref, torch.Tensor) else list(ref)
        bad = []
        for i, (g, r) in enumerate(zip(got, ref)):
            d = float(np.abs(g - r.numpy()).max())
            print(f"  check {path.name} out{i}: max|onnx-torch| = {d:.2e} (max|ref| = {float(r.abs().max()):.2f})")
            if not d <= tol * max(1.0, float(r.abs().max())):
                bad.append(f"output {i} ({d:.3g})")
        if bad:  # every output is printed first, so one CI run shows the whole picture
            raise SystemExit(f"ERROR: {path.name} differs from PyTorch: " + ", ".join(bad))

    check = "--no-check" not in flags
    rnd = torch.randn
    with torch.no_grad():
        x = rnd(1, 3, S, S)
        o = sam.image_encoder(x)
        print("fpn shapes:", [tuple(f.shape) for f in o["backbone_fpn"]], "image_size:", sam.image_size)
        enc, dec, dtr = (out / n for n in ("sam-encoder.onnx", "sam-decoder.onnx", "sam-decoder-trk.onnx"))
        export(Encoder(sam), (x,), enc, ["image"], ["high_res_feats_0", "high_res_feats_1", "image_embed", "image_feat"])
        feats = Encoder(sam)(x)
        dnames = ["image_embed", "high_res_feats_0", "high_res_feats_1", "point_coords", "point_labels"]
        onames = ["low_res_masks", "iou_predictions", "obj_ptrs", "object_score"]
        for path, multi, n in ((dec, True, 3), (dtr, False, 1)):
            coords = torch.tensor([[[400.0, 400.0], [600.0, 600.0], [500.0, 500.0]][:n]])
            labels = torch.tensor([[2.0, 3.0, 1.0][:n]]) if multi else torch.tensor([[-1.0]])
            args = (feats[2], feats[0], feats[1], coords, labels)
            export(Decoder(sam, multi), args, path, dnames, onames)
            if check:
                ort_check(path, Decoder(sam, multi), args, dnames)
        if check:
            ort_check(enc, Encoder(sam), (x,), ["image"])
        written = [enc, dec, dtr]

        if "--no-memory" not in flags:
            me = MemEnc(sam)
            lm = rnd(1, 1, 256, 256)
            sc = torch.full((1, 1), 5.0)
            mf, mp = me.run(feats[3], lm, sc)
            mem_pos = mp[-1] if isinstance(mp, (list, tuple)) else mp
            print("memory feature:", tuple(mf.shape), "pos:", tuple(mem_pos.shape))
            menc = out / "sam-memenc.onnx"
            export(me, (feats[3], lm, sc), menc, ["image_feat", "low_res_mask", "object_score"], ["mem_feat"])
            if check:
                ort_check(menc, me, (feats[3], lm, sc), ["image_feat", "low_res_mask", "object_score"])
            k, p = int(sam.num_maskmem), 16
            if int(getattr(sam, "max_obj_ptrs_in_encoder", p)) < p:
                raise SystemExit("ERROR: max_obj_ptrs_in_encoder < 16, the slot layout needs it")
            pos_flat = o["vision_pos_enc"][-1].flatten(2).permute(2, 0, 1).detach()
            ma = MemAttn(sam, pos_flat, mem_pos.detach(), k, p).eval()
            sam.eval()
            a = (feats[3], rnd(k, *mf.shape[1:]), rnd(p, 256))
            ref = ma(*a)  # original complex RoPE
            n = _patch_rope_real(sam)
            if n == 0:
                raise SystemExit("ERROR: no apply_rotary_enc found in the sam2 package: cannot rewrite RoPE real-valued")
            new = ma(*a)
            d = float((ref - new).abs().max())
            print(f"RoPE real-valued rewrite: {n} function(s) patched, max|diff| = {d:.2e}")
            if not d < 2e-3 * max(1.0, float(ref.abs().max())):
                raise SystemExit("ERROR: real-valued RoPE differs from the original: the model uses another RoPE variant")
            matt = out / "sam-memattn.onnx"
            export(ma, a, matt, ["image_feat", "mem_feats", "ptrs"], ["image_embed"])
            if check:
                ort_check(matt, ma, a, ["image_feat", "mem_feats", "ptrs"])
            written += [menc, matt]
    ckpt.unlink(missing_ok=True)
    for pth in written:
        if not pth.is_file() or pth.stat().st_size < MIN_BYTES // 5:
            print(f"ERROR: export failed or too small: {pth}", file=sys.stderr)
            return 1
        print(f"ready: {pth} ({pth.stat().st_size / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
