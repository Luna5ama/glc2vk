package dev.vibris.mod;

import com.google.common.collect.ImmutableSet;

public interface VibrisPassInfo {
	String vibris$name();

	ImmutableSet<Integer> vibris$flipsAfterPass();
}
