package org.adelheid.oneiro.api.binding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an event payload whose event only fires when a manifest flag is set, such as {@code hasSettingsIcon} for
 * {@code SettingsLayerReady}. Subscribing to it fails to compile unless the build sets the flag.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface LuaManifestFlag {

	/** The flag's manifest name, such as {@code hasSettingsIcon} or {@code hasRibbonIcon}. */
	String value();
}
