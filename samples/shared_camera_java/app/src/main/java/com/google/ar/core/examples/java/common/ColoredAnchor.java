package com.google.ar.core.examples.java.common;

import com.google.ar.core.Anchor;

public class ColoredAnchor {
	public final Anchor anchor;
	public final float[] color;

	public ColoredAnchor(Anchor a, float[] color4f) {
		this.anchor = a;
		this.color = color4f;
	}
}
