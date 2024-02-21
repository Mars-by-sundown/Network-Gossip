start cmd /c javac Gossip.java
SLEEP 1
for /l %%x in (0, 1, 2) do (
   start cmd /k java Gossip %%x
)