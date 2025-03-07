package io.github.vipcxj.jasync.core.javac.patch.java9;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.util.Name;
import io.github.vipcxj.jasync.core.javac.patch.spi.SymbolHelper;
import io.github.vipcxj.jasync.utils.patch.IPatch;

import java.util.function.Predicate;

@AutoService(SymbolHelper.class)
public class SymbolHelperImpl implements SymbolHelper {

    private IPatch getPatch() {
        return Patches.INSTANCE.getPatch();
    }

    @Override
    public Symbol getSymbol(Symbol.TypeSymbol classSymbol, Name name, Predicate<Symbol> filter) {
        return getPatch().getSymbol(classSymbol, name, filter);
    }

    @Override
    public int supportVersion() {
        return 9;
    }
}
