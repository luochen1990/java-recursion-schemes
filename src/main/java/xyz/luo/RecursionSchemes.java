/* Copyright 2020 LuoChen (luochen1990@gmail.com). Apache License 2.0 */

package xyz.luo;

import io.vavr.Tuple2;
import io.vavr.collection.List;
import io.vavr.control.Either;
import lombok.AllArgsConstructor;
import lombok.Value;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.function.Function;

public abstract class RecursionSchemes {

    @Value(staticConstructor = "of")
    public static class Limb<T, A> {
        public Class<? extends T> tagClass;
        public List<Tuple2<Field, Either<Object, A>>> fields;

        public final String tagName() {
            return tagClass.getSimpleName();
        }
        public final List<Tuple2<Field, Object>> labels() {
            return fields.filter(p -> p._2.isLeft()).map(p -> p.map2(Either::getLeft));
        }
        public final List<Tuple2<Field, A>> children() {
            return fields.filter(p -> p._2.isRight()).map(p -> p.map2(Either::get));
        }
        public final Object getLabel(String key) {
            return fields.filter(p -> p._1.getName().equals(key)).head()._2.getLeft();
        }
        public final A getChild(String key) {
            return fields.filter(p -> p._1.getName().equals(key)).head()._2.get();
        }

        public <B> Limb<T, B> map(Function<A, B> f) {
            return Limb.of(tagClass, fields.map(p -> p.map2(e -> e.map(f))));
        }

        public static <T, A> Limb<T, A> of(Class<? extends T> tagClass, List<Object> labels, List<A> children, Type typeOfT) {
            final List<Field> fieldList = List.of(tagClass.getDeclaredFields());

            ArrayList<Tuple2<Field, Either<Object, A>>> fields = new ArrayList<>();
            int usedLabelCnt = -1;
            int usedChildCnt = -1;
            for (Field fld : fieldList) {
                if (fld.getGenericType().getTypeName().equals(typeOfT.getTypeName())) {
                    usedChildCnt += 1;
                    fields.add(new Tuple2<>(fld, Either.right(children.get(usedChildCnt))));
                } else {
                    usedLabelCnt += 1;
                    fields.add(new Tuple2<>(fld, Either.left(labels.get(usedLabelCnt))));
                }
            }
            return Limb.of(tagClass, List.ofAll(fields));
        }

        @SuppressWarnings("unchecked")
        public static <T> Limb<T, T> project(Object obj, Type typeOfT) {
            final List<Field> fieldList = List.of(obj.getClass().getDeclaredFields());

            List<Tuple2<Field, Either<Object, T>>> fields = fieldList.map(fld -> {
                try {
                    if (isSameType(fld.getGenericType(), typeOfT)) {
                        fld.setAccessible(true); //NOTE: in case the field is private
                        return new Tuple2<>(fld, Either.right((T) fld.get(obj)));
                    } else {
                        fld.setAccessible(true); //NOTE: in case the field is private
                        return new Tuple2<>(fld, Either.left(fld.get(obj)));
                    }
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                    throw new RuntimeException("error(1) occurred in Limb.project()");
                }
            });
            return (Limb<T, T>) Limb.of(obj.getClass(), fields);
        }

        @SuppressWarnings("unchecked")
        public T embed() {
            try {
                Class<?>[] constructorArgTypes = fields.map(p -> p._1.getType()).toJavaArray(Class[]::new);
                Constructor<?> constructor = tagClass.getDeclaredConstructor(constructorArgTypes);
                constructor.setAccessible(true);
                Object[] args = fields.map(p -> p._2.fold(Function.identity(), (x -> x))).toJavaArray(Object[]::new);
                Object node = constructor.newInstance(args);
                return (T) node;
            } catch (IllegalAccessException | InstantiationException | NoSuchMethodException | InvocationTargetException e) {
                throw new RuntimeException("error(1) occurred in Limb::embed()", e);
            }
        }
    }

    public static <T, R> Function<T, R> cata(Function<Limb<T, R>, R> f, Type typeOfT) {
        return tree -> {
            Limb<T, T> limb = Limb.project(tree, typeOfT);
            return f.apply(limb.map(t -> cata(f, typeOfT).apply(t)));
        };
    }

    public static <T, R> Function<T, R> para(Function<Limb<T, Tuple2<T, R>>, R> f, Type typeOfT) {
        return tree -> {
            Limb<T, T> limb = Limb.project(tree, typeOfT);
            return f.apply(limb.map(t -> new Tuple2<>(t, para(f, typeOfT).apply(t))));
        };
    }

    public static <T, S> Function<S, T> ana(final Function<S, Limb<T, S>> g) {
        return seed -> {
            Limb<T, S> limb = g.apply(seed);
            return limb.map(s -> ana(g).apply(s)).embed();
        };
    }

    public static <T, S> Function<S, T> apo(final Function<S, Either<Limb<T, S>, T>> g) {
        Box<Function<S, T>> apoG = Box.of(null);
        apoG.set(seed -> {
            Either<Limb<T, S>, T> limbE = g.apply(seed);
            return limbE.fold(limb -> limb.map(apoG.get()).embed(), Function.identity());
        });
        return apoG.get();
    }

    @AllArgsConstructor(staticName = "of")
    private static final class Box<A> {
        A value;
        public A get() {
            return value;
        }
        public void set(A newValue) {
            value = newValue;
        }
    }

    /** NOTE: isSameType() is an workaround for a JDK bug about reflection */
    private static boolean isSameType(Type a, Type b) {
        return getTypenameSuffix(a.getTypeName()).equals(getTypenameSuffix(b.getTypeName()));
    }

    private static String getTypenameSuffix(String typename) {
        int i = typename.lastIndexOf(".");
        return typename.substring(i+1);
    }
}
