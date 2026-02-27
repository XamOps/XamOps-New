import re

with open('src/main/java/com/xammer/cloud/service/FinOpsService.java', 'r') as f:
    code = f.read()

def replace_method(m):
    body = m.group(2)
    # Replace return CompletableFuture.completedFuture(X); with return X;
    body = re.sub(r'return\s+CompletableFuture\.completedFuture\((.*?)\);', r'return \1;', body)
    
    # Wrap body in CompletableFuture.supplyAsync
    new_method = m.group(1) + " {\n        return CompletableFuture.supplyAsync(() -> {" + body + "\n        }, TenantContextUtil.tenantAware(awsTaskExecutor));\n    }"
    return new_method

# We find the methods by their signature and block them out
pattern = r'(\s*@Async\("awsTaskExecutor"\)\s*public\s+CompletableFuture<[^>]+>\s+\w+\(.*?\))\s*\{((?:[^{}]*|\{[^{}]*\})*?)\n\s*\}'

# This naive regex for the body is weak, let's do something better.
