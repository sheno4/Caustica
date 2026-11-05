/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.internal;

import dev.comfyfluffy.cloudly.api.resource.ResourceOwner;

/** Current-frame atmosphere descriptors borrowed from the retained sky binding. */
public record CloudlySkyLighting(long frameIndex, int ambientIndex, int transmittanceIndex,
                                 int samplerIndex, ResourceOwner owner) { }
