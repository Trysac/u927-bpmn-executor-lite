package io.github.trysac.bpmn_executor_lite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hand-written lexer, recursive-descent parser, and evaluator for BPMN sequenceFlow conditions.
 *
 * <p>Supported syntax: variable identifiers, single-quoted string literals, signed/unsigned integer
 * literals, boolean literals ({@code true}, {@code false}), comparison operators ({@code ==},
 * {@code !=}, {@code <}, {@code <=}, {@code >}, {@code >=}), boolean operators ({@code and}, {@code
 * or}, {@code not}), and parentheses. Method calls, property dereferencing, and unknown syntax
 * raise {@link BpmnException} with code {@code "invalid_condition"}.
 */
final class ConditionEvaluator {

  private static final int MAX_EXPRESSION_LENGTH = 4096;
  private static final int MAX_AST_DEPTH = 64;

  private ConditionEvaluator() {}

  static Expr compile(String input) {
    if (input == null) {
      throw new BpmnException("invalid_condition", "Condition expression is null");
    }
    String trimmed = stripOptionalExpressionWrapper(input.strip());
    if (trimmed.isEmpty() || trimmed.length() > MAX_EXPRESSION_LENGTH) {
      throw new BpmnException("invalid_condition", "Condition expression is empty or too long");
    }
    List<Token> tokens = tokenize(trimmed);
    Parser parser = new Parser(tokens);
    Expr expr = parser.parseExpression(0);
    if (!parser.isAtEnd()) {
      throw new BpmnException(
          "invalid_condition", "Unexpected token in condition: " + parser.peek().text());
    }
    return expr;
  }

  static boolean evaluate(Expr expr, Map<String, Object> variables) {
    Object val = expr.eval(variables);
    if (val instanceof Boolean b) {
      return b;
    }
    throw new BpmnException(
        "invalid_condition", "Condition expression did not evaluate to a boolean");
  }

  private static String stripOptionalExpressionWrapper(String s) {
    if ((s.startsWith("${") || s.startsWith("#{")) && s.endsWith("}") && s.length() >= 3) {
      return s.substring(2, s.length() - 1).strip();
    }
    return s;
  }

  private enum TokenKind {
    IDENT,
    INT,
    STRING,
    BOOL,
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE,
    AND,
    OR,
    NOT,
    LPAREN,
    RPAREN,
    MINUS
  }

  private record Token(TokenKind kind, String text, Object literal) {}

  private static List<Token> tokenize(String s) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    int n = s.length();
    while (i < n) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '(') {
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).kind() == TokenKind.IDENT) {
          throw new BpmnException(
              "invalid_condition", "Method calls are forbidden in condition expressions");
        }
        tokens.add(new Token(TokenKind.LPAREN, "(", null));
        i++;
      } else if (c == ')') {
        tokens.add(new Token(TokenKind.RPAREN, ")", null));
        i++;
      } else if (c == '-') {
        tokens.add(new Token(TokenKind.MINUS, "-", null));
        i++;
      } else if (c == '=' && i + 1 < n && s.charAt(i + 1) == '=') {
        tokens.add(new Token(TokenKind.EQ, "==", null));
        i += 2;
      } else if (c == '!' && i + 1 < n && s.charAt(i + 1) == '=') {
        tokens.add(new Token(TokenKind.NE, "!=", null));
        i += 2;
      } else if (c == '<' && i + 1 < n && s.charAt(i + 1) == '=') {
        tokens.add(new Token(TokenKind.LE, "<=", null));
        i += 2;
      } else if (c == '>' && i + 1 < n && s.charAt(i + 1) == '=') {
        tokens.add(new Token(TokenKind.GE, ">=", null));
        i += 2;
      } else if (c == '<') {
        tokens.add(new Token(TokenKind.LT, "<", null));
        i++;
      } else if (c == '>') {
        tokens.add(new Token(TokenKind.GT, ">", null));
        i++;
      } else if (c == '\'') {
        int start = ++i;
        StringBuilder sb = new StringBuilder();
        boolean closed = false;
        while (i < n) {
          char ch = s.charAt(i++);
          if (ch == '\'') {
            closed = true;
            break;
          }
          if (ch == '\\' && i < n) {
            sb.append(s.charAt(i++));
          } else {
            sb.append(ch);
          }
        }
        if (!closed) {
          throw new BpmnException(
              "invalid_condition", "Unterminated string literal at index " + (start - 1));
        }
        tokens.add(new Token(TokenKind.STRING, sb.toString(), sb.toString()));
      } else if (Character.isDigit(c)) {
        int start = i;
        while (i < n && Character.isDigit(s.charAt(i))) {
          i++;
        }
        if (i < n
            && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '_')) {
          throw new BpmnException("invalid_condition", "Invalid numeric token in condition");
        }
        String numStr = s.substring(start, i);
        try {
          long val = Long.parseLong(numStr);
          tokens.add(new Token(TokenKind.INT, numStr, val));
        } catch (NumberFormatException e) {
          throw new BpmnException(
              "invalid_condition", "Integer literal out of range: " + numStr, e);
        }
      } else if (isIdentStart(c)) {
        int start = i;
        while (i < n && isIdentPart(s.charAt(i))) {
          i++;
        }
        if (i < n && s.charAt(i) == '.') {
          throw new BpmnException(
              "invalid_condition", "Member access is forbidden in condition expressions");
        }
        String ident = s.substring(start, i);
        switch (ident) {
          case "and" -> tokens.add(new Token(TokenKind.AND, ident, null));
          case "or" -> tokens.add(new Token(TokenKind.OR, ident, null));
          case "not" -> tokens.add(new Token(TokenKind.NOT, ident, null));
          case "true" -> tokens.add(new Token(TokenKind.BOOL, ident, Boolean.TRUE));
          case "false" -> tokens.add(new Token(TokenKind.BOOL, ident, Boolean.FALSE));
          default -> tokens.add(new Token(TokenKind.IDENT, ident, null));
        }
      } else {
        throw new BpmnException(
            "invalid_condition", "Illegal character '" + c + "' in condition expression");
      }
    }
    return tokens;
  }

  private static boolean isIdentStart(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
  }

  private static boolean isIdentPart(char c) {
    return isIdentStart(c) || (c >= '0' && c <= '9');
  }

  sealed interface Expr {
    Object eval(Map<String, Object> vars);
  }

  private record LiteralExpr(Object value) implements Expr {
    @Override
    public Object eval(Map<String, Object> vars) {
      return value;
    }
  }

  private record VarExpr(String name) implements Expr {
    @Override
    public Object eval(Map<String, Object> vars) {
      if (!vars.containsKey(name)) {
        throw new BpmnException("invalid_condition", "Unknown variable in condition: " + name);
      }
      return vars.get(name);
    }
  }

  private record NotExpr(Expr inner) implements Expr {
    @Override
    public Object eval(Map<String, Object> vars) {
      Object v = inner.eval(vars);
      if (v instanceof Boolean b) {
        return !b;
      }
      throw new BpmnException("invalid_condition", "Operand of 'not' must be boolean");
    }
  }

  private record LogicalExpr(TokenKind op, Expr left, Expr right) implements Expr {
    @Override
    public Object eval(Map<String, Object> vars) {
      Object lv = left.eval(vars);
      if (!(lv instanceof Boolean lb)) {
        throw new BpmnException(
            "invalid_condition", "Left operand of boolean operator must be boolean");
      }
      if (op == TokenKind.AND) {
        if (!lb) {
          return Boolean.FALSE;
        }
        Object rv = right.eval(vars);
        if (!(rv instanceof Boolean rb)) {
          throw new BpmnException("invalid_condition", "Right operand of 'and' must be boolean");
        }
        return rb;
      } else {
        if (lb) {
          return Boolean.TRUE;
        }
        Object rv = right.eval(vars);
        if (!(rv instanceof Boolean rb)) {
          throw new BpmnException("invalid_condition", "Right operand of 'or' must be boolean");
        }
        return rb;
      }
    }
  }

  private record CompareExpr(TokenKind op, Expr left, Expr right) implements Expr {
    @Override
    public Object eval(Map<String, Object> vars) {
      Object lv = left.eval(vars);
      Object rv = right.eval(vars);
      if (lv instanceof Number ln && rv instanceof Number rn) {
        long l = ln.longValue();
        long r = rn.longValue();
        return switch (op) {
          case EQ -> l == r;
          case NE -> l != r;
          case LT -> l < r;
          case LE -> l <= r;
          case GT -> l > r;
          case GE -> l >= r;
          default -> throw new BpmnException("invalid_condition", "Unsupported operator: " + op);
        };
      }
      if (op == TokenKind.EQ || op == TokenKind.NE) {
        boolean eq = Objects.equals(lv, rv);
        return op == TokenKind.EQ ? eq : !eq;
      }
      if (lv instanceof String ls && rv instanceof String rs) {
        int cmp = ls.compareTo(rs);
        return switch (op) {
          case LT -> cmp < 0;
          case LE -> cmp <= 0;
          case GT -> cmp > 0;
          case GE -> cmp >= 0;
          default -> throw new BpmnException("invalid_condition", "Unsupported operator: " + op);
        };
      }
      throw new BpmnException(
          "invalid_condition", "Incomparable operands in condition for operator " + op);
    }
  }

  private static final class Parser {
    private final List<Token> tokens;
    private int pos = 0;

    Parser(List<Token> tokens) {
      this.tokens = tokens;
    }

    boolean isAtEnd() {
      return pos >= tokens.size();
    }

    Token peek() {
      return tokens.get(pos);
    }

    Token advance() {
      return tokens.get(pos++);
    }

    boolean match(TokenKind kind) {
      if (!isAtEnd() && peek().kind() == kind) {
        pos++;
        return true;
      }
      return false;
    }

    Expr parseExpression(int depth) {
      checkDepth(depth);
      return parseOr(depth + 1);
    }

    private Expr parseOr(int depth) {
      checkDepth(depth);
      Expr expr = parseAnd(depth + 1);
      while (match(TokenKind.OR)) {
        Expr right = parseAnd(depth + 1);
        expr = new LogicalExpr(TokenKind.OR, expr, right);
      }
      return expr;
    }

    private Expr parseAnd(int depth) {
      checkDepth(depth);
      Expr expr = parseNot(depth + 1);
      while (match(TokenKind.AND)) {
        Expr right = parseNot(depth + 1);
        expr = new LogicalExpr(TokenKind.AND, expr, right);
      }
      return expr;
    }

    private Expr parseNot(int depth) {
      checkDepth(depth);
      if (match(TokenKind.NOT)) {
        return new NotExpr(parseNot(depth + 1));
      }
      return parseComparison(depth + 1);
    }

    private Expr parseComparison(int depth) {
      checkDepth(depth);
      Expr left = parsePrimary(depth + 1);
      if (!isAtEnd() && isComparisonOp(peek().kind())) {
        TokenKind op = advance().kind();
        Expr right = parsePrimary(depth + 1);
        if (!isAtEnd() && isComparisonOp(peek().kind())) {
          throw new BpmnException(
              "invalid_condition", "Chained comparisons are not supported in condition");
        }
        return new CompareExpr(op, left, right);
      }
      return left;
    }

    private Expr parsePrimary(int depth) {
      checkDepth(depth);
      if (isAtEnd()) {
        throw new BpmnException("invalid_condition", "Unexpected end of condition expression");
      }
      if (match(TokenKind.LPAREN)) {
        Expr inner = parseExpression(depth + 1);
        if (!match(TokenKind.RPAREN)) {
          throw new BpmnException("invalid_condition", "Missing closing parenthesis in condition");
        }
        return inner;
      }
      if (match(TokenKind.MINUS)) {
        if (isAtEnd() || peek().kind() != TokenKind.INT) {
          throw new BpmnException(
              "invalid_condition", "Unary minus is only allowed before an integer literal");
        }
        Token intTok = advance();
        long val = (Long) intTok.literal();
        return new LiteralExpr(-val);
      }
      Token t = advance();
      return switch (t.kind()) {
        case INT, STRING, BOOL -> new LiteralExpr(t.literal());
        case IDENT -> new VarExpr(t.text());
        default ->
            throw new BpmnException(
                "invalid_condition", "Unexpected token in condition: " + t.text());
      };
    }

    private static boolean isComparisonOp(TokenKind k) {
      return k == TokenKind.EQ
          || k == TokenKind.NE
          || k == TokenKind.LT
          || k == TokenKind.LE
          || k == TokenKind.GT
          || k == TokenKind.GE;
    }

    private static void checkDepth(int depth) {
      if (depth > MAX_AST_DEPTH) {
        throw new BpmnException("invalid_condition", "Condition expression exceeds nesting limit");
      }
    }
  }
}
