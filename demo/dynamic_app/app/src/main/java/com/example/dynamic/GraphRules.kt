package com.example.dynamic

import com.kite.di.rules.Root

/**
 * The reason this fixture needs the vocabulary on its compile classpath at all: a
 * plain class resolved only at runtime has no constructor parameter pointing at it, so
 * nothing pulls it into the graph — and without the root it gets no generated accessor.
 *
 * SOURCE retention means no annotation reference survives compilation, which is why
 * R8 can delete these classes from the release even though AGP forced them onto the
 * `implementation` configuration here.
 */
@Root(LegacyTracker::class)
private object GraphRules
