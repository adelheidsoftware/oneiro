package org.adelheid.oneiro.java;

import static org.adelheid.oneiro.lua.Expression.field;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.adelheid.oneiro.lua.Block;
import org.adelheid.oneiro.lua.Comment;
import org.adelheid.oneiro.lua.Trivia;
import org.jspecify.annotations.Nullable;

/**
 * The comments that go with a member into Lua: the source comments above it, its Javadoc description, and the Lua
 * Language Server annotations its signature gives, {@code ---@param} and {@code ---@return}. A comment on
 * something that has no Lua of its own cannot be placed and gets a warning instead.
 */
final class MemberComments {

	private static final Pattern NAMED_RETURN = Pattern.compile("([A-Za-z_]\\w*):\\s+(.+)", Pattern.DOTALL);

	private final UnitLowering unit;
	private final JavaTypes types;

	MemberComments(UnitLowering unit) {
		this.unit = unit;
		this.types = unit.types;
	}

	/** The trivia with more comment lines above the ones it has. */
	static Trivia withComments(Trivia trivia, @Nullable List<Comment> lines) {
		if (lines == null || lines.isEmpty()) {
			return trivia;
		}
		List<Comment> leading = new ArrayList<>(lines);
		leading.addAll(trivia.leading());
		return new Trivia(trivia.blankLineBefore(), leading, trivia.trailing());
	}

	/** The member with more comment lines above its first statement. */
	static UnitLowering.Member withComments(UnitLowering.Member member, List<Comment> lines) {
		return new UnitLowering.Member(member.elements(), member.function(), member.localName(),
				withFirstComments(member.entries(), lines), withFirstComments(member.assignForm(), lines),
				member.references(), member.after());
	}

	static List<Block.Entry> withFirstComments(List<Block.Entry> entries, List<Comment> lines) {
		if (entries.isEmpty()) {
			return entries;
		}
		List<Block.Entry> changed = new ArrayList<>(entries);
		Block.Entry first = changed.getFirst();
		changed.set(0, new Block.Entry(first.statement(), withComments(first.trivia(), lines), first.origin()));
		return changed;
	}

	/** Comments on a member without Lua of its own cannot be placed; each gets a warning saying so. */
	void dropComments(List<CommentIndex.SourceComment> leading, String message) {
		leading.forEach(comment -> unit.warning(comment.start(), message));
	}

	/** A field's comments: the source comments before it and its Javadoc description. */
	List<Comment> fieldComments(List<CommentIndex.SourceComment> leading, VariableTree field) {
		List<Comment> lines = new ArrayList<>(BodyLowering.comments(leading));
		DocCommentTree doc = unit.trees.getDocCommentTree(unit.path(field));
		if (doc != null) {
			DocText.lines(doc.getFullBody()).forEach(line -> lines.add(Comment.line(line)));
		}
		return lines;
	}

	/** A nested type's comments: the source comments before it and its Javadoc description. */
	Trivia typeTrivia(boolean blankLine, List<CommentIndex.SourceComment> leading, ClassTree type) {
		List<Comment> lines = new ArrayList<>(BodyLowering.comments(leading));
		DocCommentTree doc = unit.trees.getDocCommentTree(unit.path(type));
		if (doc != null) {
			DocText.lines(doc.getFullBody()).forEach(line -> lines.add(Comment.line(line)));
		}
		return new Trivia(blankLine, lines, null);
	}

	/**
	 * A member's comments: the source comments before it, its Javadoc description, and its annotations. Parameters a
	 * default chain fills in are optional: a question mark follows their names.
	 */
	Trivia memberTrivia(boolean blankLine, List<CommentIndex.SourceComment> leading, MethodTree method,
			Overloading.@Nullable DefaultChain chain) {
		List<Comment> lines = new ArrayList<>(BodyLowering.comments(leading));
		DocCommentTree doc = unit.trees.getDocCommentTree(unit.path(method));
		if (doc != null) {
			DocText.lines(doc.getFullBody()).forEach(line -> lines.add(Comment.line(line)));
			ExecutableElement element = (ExecutableElement) unit.element(method);
			List<? extends VariableTree> parameters = method.getParameters();
			for (int index = 0; index < parameters.size(); index++) {
				VariableTree parameter = parameters.get(index);
				TypeMirror type = unit.type(parameter);
				String text = DocText.param(doc, parameter.getName().toString());
				String optional = chain != null && chain.defaults().containsKey(index) ? "?" : "";
				// A varargs parameter is Lua's ..., each value of the array's type
				boolean varargs = element.isVarArgs() && index == parameters.size() - 1;
				String luaName = varargs ? "..." : unit.names.luaName(parameter.getName().toString());
				TypeMirror annotated = varargs ? ((ArrayType) type).getComponentType() : type;
				lines.add(Comment.annotation(("@param " + luaName + optional + " "
						+ types.luaType(annotated, types.isNullable(annotated)) + " " + text).stripTrailing()));
			}
			TypeMirror returnType = element.getReturnType();
			if (types.isReturnValues(returnType)) {
				// Several values: one annotation per record component, named after it
				TypeElement record = (TypeElement) ((DeclaredType) returnType).asElement();
				for (var component : record.getRecordComponents()) {
					TypeMirror componentType = component.asType();
					lines.add(Comment.annotation("@return "
							+ types.luaType(componentType, types.isNullable(componentType)) + " "
							+ component.getSimpleName()));
				}
			} else if (returnType.getKind() != TypeKind.VOID && !UnitLowering.isConstructor(method)) {
				String luaType = types.luaType(returnType, types.isNullable(returnType));
				lines.add(Comment.annotation(returnAnnotation(luaType, DocText.returned(doc))));
			}
		}
		return new Trivia(blankLine, lines, null);
	}

	/**
	 * {@code @return boolean # text}, or with the doc-comment convention {@code @return complete: text} a named return,
	 * {@code @return boolean complete text}.
	 */
	private static String returnAnnotation(String luaType, String text) {
		Matcher named = NAMED_RETURN.matcher(text);
		if (named.matches()) {
			return "@return " + luaType + " " + named.group(1) + " " + named.group(2);
		}
		return "@return " + luaType + (text.isEmpty() ? "" : " # " + text);
	}
}
