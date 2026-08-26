package dev.comfyfluffy.caustica.api.pass;

/**
 * Where in the post-effect chain a pass asks to sit.
 *
 * <p>Declaration order alone is load order, and load order is not something an extension can reason about
 * or a user can see the sense in. An anchor is the smallest thing that fixes the cases that actually have a
 * right answer: a grade belongs at the end of the chain, a pass that wants the untouched scene image
 * belongs at the start, and everything else genuinely does not care.
 *
 * <p><b>It is an anchor, not a priority and not a dependency.</b> There is no number to escalate and no way
 * to name another pass, so this cannot express "after theirs" — deliberately, because the rule that makes
 * the chain composable at all is that <em>a pass must work when the passes around it are absent</em>. Two
 * passes claiming the same end run in declaration order, which is arbitrary and is the honest outcome: they
 * both wanted the same place and only one can have it.
 */
public enum PassAnchor {
    /** Before every unpinned pass. For a pass that wants the scene image as reconstruction left it. */
    FIRST,
    /** No opinion, and where all but a handful of passes belong. */
    MIDDLE,
    /** After every unpinned pass. For a scene-referred grade, which ACES puts last before the output transform. */
    LAST
}
