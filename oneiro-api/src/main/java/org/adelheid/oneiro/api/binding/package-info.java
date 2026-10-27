/**
 * The annotations that only generated bindings carry, which tell the compiler how Lua calls the members of the RS3
 * API, of the libraries a plugin uses and of its own Lua. A plugin's own classes may not carry them: the Lua a plugin
 * calls is bound from its LuaCATS annotations.
 */
@NullMarked
package org.adelheid.oneiro.api.binding;

import org.jspecify.annotations.NullMarked;
