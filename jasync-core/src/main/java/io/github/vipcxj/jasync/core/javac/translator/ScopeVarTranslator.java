package io.github.vipcxj.jasync.core.javac.translator;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import io.github.vipcxj.jasync.core.javac.Constants;
import io.github.vipcxj.jasync.core.javac.IJAsyncInstanceContext;
import io.github.vipcxj.jasync.core.javac.JavacUtils;
import io.github.vipcxj.jasync.core.javac.model.VarInfo;
import io.github.vipcxj.jasync.core.javac.model.VarKey;
import io.github.vipcxj.jasync.core.javac.model.VarUseState;

import javax.lang.model.element.Element;
import java.util.Map;

public class ScopeVarTranslator extends TreeTranslator {

    private final IJAsyncInstanceContext context;
    private final Map<VarKey, VarInfo> varData;

    public ScopeVarTranslator(IJAsyncInstanceContext context, Map<VarKey, VarInfo> varData) {
        this.context = context;
        this.varData = varData;
    }

    private VarInfo getVarInfo(JCTree tree) {
        Element element = context.getElement(tree);
        Symbol.VarSymbol varSymbol = null;
        if (element instanceof Symbol.VarSymbol) {
            varSymbol = (Symbol.VarSymbol) element;
        } else if (tree instanceof JCTree.JCIdent) {
            // 如果tree和ast断开关联，getElement返回必定为空。但若tree本身就已经解析过Symbol了，可以直接用
            // 但这里的处理过于魔法，很容易出问题，如果有更好的办法，最好改掉。
            JCTree.JCIdent ident = (JCTree.JCIdent) tree;
            if (ident.sym instanceof Symbol.VarSymbol) {
                varSymbol = (Symbol.VarSymbol) ident.sym;
            }
        }
        if (varSymbol != null) {
            for (VarInfo info : varData.values()) {
                if (JavacUtils.equalSymbol(context, varSymbol, info.getSymbol())) {
                    return info;
                }
            }
        }
        return null;
    }

    @Override
    public void visitIdent(JCTree.JCIdent jcIdent) {
        VarInfo varInfo = getVarInfo(jcIdent);
        if (varInfo != null) {
            jcIdent.sym = null;
            if (varInfo.getState() == VarUseState.WRITE) {
                TreeMaker maker = context.getTreeMaker();
                Names names = context.getNames();
                int prePos = maker.pos;
                result = maker.at(jcIdent).Apply(
                        List.nil(),
                        maker.Select(
                                maker.Ident(jcIdent.name),
                                names.fromString(Constants.REFERENCE_GET)
                        ),
                        List.nil()
                );
                maker.pos = prePos;
                return;
            }
        }
        super.visitIdent(jcIdent);
    }

    @Override
    public void visitAssign(JCTree.JCAssign jcAssign) {
        VarInfo varInfo = getVarInfo(jcAssign.lhs);
        if (varInfo != null) {
            TreeMaker maker = context.getTreeMaker();
            Names names = context.getNames();
            if (varInfo.getState() == VarUseState.WRITE) {
                JCTree.JCExpression rhs = translate(jcAssign.rhs);
                int prePos = maker.pos;
                result = maker.at(jcAssign).Apply(
                        List.nil(),
                        maker.at(jcAssign.lhs).Select(
                                maker.Ident(varInfo.getSymbol().name),
                                names.fromString(Constants.REFERENCE_ASSIGN)
                        ),
                        List.of(rhs)
                );
                maker.pos = prePos;
                return;
            }
        }
        super.visitAssign(jcAssign);
    }

    @Override
    public void visitAssignop(JCTree.JCAssignOp jcAssignOp) {
        VarInfo varInfo = getVarInfo(jcAssignOp.lhs);
        if (varInfo != null) {
            JCTree.JCExpression rhs = translate(jcAssignOp.rhs);
            if (varInfo.getState() == VarUseState.WRITE) {
                String assignMethod = JavacUtils.getAssignMethod(jcAssignOp.getTag());
                TreeMaker maker = context.getTreeMaker();
                Names names = context.getNames();
                int prePos = maker.pos;
                result = maker.at(jcAssignOp).Apply(
                        List.nil(),
                        maker.at(jcAssignOp.lhs).Select(
                                maker.Ident(varInfo.getSymbol().name),
                                names.fromString(assignMethod)
                        ),
                        List.of(rhs)
                );
                maker.pos = prePos;
                return;
            }
        }
        super.visitAssignop(jcAssignOp);
    }

    @Override
    public void visitUnary(JCTree.JCUnary jcUnary) {
        VarInfo varInfo = getVarInfo(jcUnary.arg);
        if (varInfo != null) {
            if (varInfo.getState() == VarUseState.WRITE) {
                String assignMethod = JavacUtils.getAssignMethod(jcUnary.getTag());
                TreeMaker maker = context.getTreeMaker();
                Names names = context.getNames();
                int prePos = maker.pos;
                result = maker.at(jcUnary).Apply(
                        List.nil(),
                        maker.at(jcUnary.arg).Select(
                                maker.Ident(varInfo.getSymbol().name),
                                names.fromString(assignMethod)
                        ),
                        List.nil()
                );
                maker.pos = prePos;
                return;
            }
        }
        super.visitUnary(jcUnary);
    }
}
