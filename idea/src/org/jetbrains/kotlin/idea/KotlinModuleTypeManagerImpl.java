/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea;

import com.intellij.facet.Facet;
import com.intellij.facet.FacetManager;
import com.intellij.openapi.module.Module;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.idea.configuration.KotlinModuleTypeManager;

public class KotlinModuleTypeManagerImpl extends KotlinModuleTypeManager {
    @Override
    public boolean isAndroidGradleModule(@NotNull Module module) {
        // We don't want to depend on the Android-Gradle plugin
        // See com.android.tools.idea.gradle.util.Projects.isGradleProject()
        boolean hasAndroidFacet = false;
        for (Facet facet : FacetManager.getInstance(module).getAllFacets()) {
            if (facet.getName().equals("Android")) {
                hasAndroidFacet = true;
            }
        }
        return hasAndroidFacet && isGradleModule(module);
    }
}
