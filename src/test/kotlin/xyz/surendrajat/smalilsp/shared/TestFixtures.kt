package xyz.surendrajat.smalilsp.shared

/**
 * Shared smali content fixtures for tests.
 * Reduces duplication of common class/method patterns across test files.
 */
object TestFixtures {

    /** Minimal valid smali class with just a constructor */
    val SIMPLE_CLASS = """
        .class public Lcom/example/Simple;
        .super Ljava/lang/Object;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method
    """.trimIndent()

    /** Class extending Object with a field and method */
    val CLASS_WITH_FIELD = """
        .class public Lcom/example/WithField;
        .super Ljava/lang/Object;

        .field private count:I

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public getCount()I
            .registers 2
            iget v0, p0, Lcom/example/WithField;->count:I
            return v0
        .end method
    """.trimIndent()

    /** Activity-like class with onCreate */
    val ACTIVITY_CLASS = """
        .class public Lcom/example/MainActivity;
        .super Landroid/app/Activity;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Landroid/app/Activity;-><init>()V
            return-void
        .end method

        .method protected onCreate(Landroid/os/Bundle;)V
            .registers 2
            invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
            return-void
        .end method
    """.trimIndent()

    /** Class implementing an interface */
    val INTERFACE_CLASS = """
        .class public interface abstract Lcom/example/Callback;
        .super Ljava/lang/Object;

        .method public abstract onResult(Ljava/lang/String;)V
        .end method
    """.trimIndent()

    /** Class that implements the interface */
    val INTERFACE_IMPL = """
        .class public Lcom/example/CallbackImpl;
        .super Ljava/lang/Object;
        .implements Lcom/example/Callback;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public onResult(Ljava/lang/String;)V
            .registers 2
            return-void
        .end method
    """.trimIndent()

    /** Class with various instruction types for navigation testing */
    val CLASS_WITH_INSTRUCTIONS = """
        .class public Lcom/example/Worker;
        .super Ljava/lang/Object;

        .field private static TAG:Ljava/lang/String;
        .field private helper:Lcom/example/Helper;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public doWork(Ljava/lang/String;I)V
            .registers 5
            const-string v0, "working"
            invoke-virtual {p0, p1}, Lcom/example/Worker;->process(Ljava/lang/String;)V
            invoke-static {p2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
            iget-object v1, p0, Lcom/example/Worker;->helper:Lcom/example/Helper;
            invoke-virtual {v1}, Lcom/example/Helper;->run()V
            return-void
        .end method

        .method private process(Ljava/lang/String;)V
            .registers 2
            return-void
        .end method
    """.trimIndent()

    /** Helper class referenced by CLASS_WITH_INSTRUCTIONS */
    val HELPER_CLASS = """
        .class public Lcom/example/Helper;
        .super Ljava/lang/Object;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public run()V
            .registers 1
            return-void
        .end method
    """.trimIndent()

    /** Class with labels and jumps for label navigation testing */
    val CLASS_WITH_LABELS = """
        .class public Lcom/example/Branching;
        .super Ljava/lang/Object;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public check(I)Z
            .registers 3
            const/4 v0, 0x0
            if-lez p1, :cond_false
            const/4 v0, 0x1
            goto :cond_end
            :cond_false
            const/4 v0, 0x0
            :cond_end
            return v0
        .end method
    """.trimIndent()

    /** Inner class pattern */
    val OUTER_CLASS = """
        .class public Lcom/example/Outer;
        .super Ljava/lang/Object;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method
    """.trimIndent()

    val INNER_CLASS = """
        .class public Lcom/example/Outer${'$'}Inner;
        .super Ljava/lang/Object;

        .field final synthetic this${'$'}0:Lcom/example/Outer;

        .method public constructor <init>(Lcom/example/Outer;)V
            .registers 2
            iput-object p1, p0, Lcom/example/Outer${'$'}Inner;->this${'$'}0:Lcom/example/Outer;
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method
    """.trimIndent()

    /** Class with array types for array navigation testing */
    val CLASS_WITH_ARRAYS = """
        .class public Lcom/example/ArrayUser;
        .super Ljava/lang/Object;

        .field private data:[I
        .field private names:[Ljava/lang/String;
        .field private matrix:[[I

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public process([Ljava/lang/String;)[I
            .registers 3
            const/4 v0, 0x5
            new-array v0, v0, [I
            return-object v0
        .end method
    """.trimIndent()

    /** Class with annotations */
    val CLASS_WITH_ANNOTATIONS = """
        .class public Lcom/example/Annotated;
        .super Ljava/lang/Object;

        .annotation system Ldalvik/annotation/MemberClasses;
            value = {
                Lcom/example/Annotated${'$'}Builder;
            }
        .end annotation

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public toString()Ljava/lang/String;
            .registers 2
            .annotation runtime Ljava/lang/Override;
            .end annotation
            const-string v0, "Annotated"
            return-object v0
        .end method
    """.trimIndent()

    /** Class with const-string instructions for string search testing */
    val CLASS_WITH_STRINGS = """
        .class public Lcom/example/Config;
        .super Ljava/lang/Object;

        .method public constructor <init>()V
            .registers 1
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            return-void
        .end method

        .method public getApiUrl()Ljava/lang/String;
            .registers 2
            const-string v0, "https://api.example.com/v1"
            return-object v0
        .end method

        .method public getKey()Ljava/lang/String;
            .registers 2
            const-string v0, "AES/CBC/PKCS5Padding"
            return-object v0
        .end method
    """.trimIndent()
}
