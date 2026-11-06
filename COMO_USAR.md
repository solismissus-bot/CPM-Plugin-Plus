# CPM Plugin+: como usar tudo

## ⌨️ Atalhos de teclado

| Atalho | Ferramenta |
|---|---|
| **Ctrl + Alt + A** | Ajustes do modelo |
| **Ctrl + Alt + B** | Pose intermediária (tween) |
| **Ctrl + Alt + D** | Atrasar / adiantar movimento |
| **Ctrl + Alt + F** | Copiar movimento de (Copy Transform) |
| **Ctrl + Alt + G** | Grudar na parte do corpo |
| **Ctrl + Alt + H** | Mostrar/esconder nesta animação |
| **Ctrl + Alt + I** | Importar animações de outro .cpmproject |
| **Ctrl + Alt + J** | Levar pro jogo |
| **Ctrl + Alt + K** | Ver esqueleto (liga/desliga) |
| **Ctrl + Alt + L** | Fechar o loop |
| **Ctrl + Alt + M** | Espelhar pro outro lado (abre a janela) |
| **Ctrl + Alt + Shift + M** | Espelhar pro outro lado + animações, direto (sem janela) |
| **Ctrl + Alt + N** | Fazer onda |
| **Ctrl + Alt + O** | Otimizar animação |
| **Ctrl + Alt + P** | Marcar pose aqui |
| **Ctrl + Alt + R** | Criar cópia espelhada da animação |
| **Ctrl + Alt + U** | 🆕 Física: seguir o movimento |
| **Ctrl + Alt + V** | Suavizar movimento |
| **Ctrl + Alt + X** | Exagerar ou suavizar movimento |
| **Ctrl + Alt + Y** | 🆕 Ajuste relativo (somar em todas as keys) |
| **Ctrl + Alt + Z** | 🆕 Editar keys em várias animações |
| **Ctrl + Alt + Shift + A** | 🆕 Camada de ajuste: criar / editar (aperte de novo pra voltar pra original) |
| **Ctrl + Alt + Shift + B** | Colar pose no outro lado (espelhada) |
| **Ctrl + Alt + Shift + C** | Copiar pose das partes selecionadas |
| **Ctrl + Alt + Shift + K** | 🆕 Conferir movimento |
| **Ctrl + Alt + Shift + N** | 🆕 Nova animação pelo que você quer |
| **Ctrl + Alt + Shift + R** | Espelhar pose |
| **Ctrl + Alt + Shift + V** | Colar pose |

🆕 = novidade recente (beta28: ferramentas de animação; beta29-30: arte e pincéis; beta31: tudo de pintura no painel CPM+ Pintar; beta32: pincéis HD e Esfumar ao lado da Borracha, seção 5). Pra trocar ou criar atalho pra qualquer outra ferramenta: **Arquivo > Configurações > Atalhos**, categoria **Ferramentas**, procure pelo nome. Se um atalho não funcionar, veja nessa mesma tela se outra ferramenta (de outro plugin, por exemplo) usa a mesma tecla e troque uma delas.

Quase tudo fica no menu **CPM+** (barra de cima do Blockbench), dividido em: **Ajustes do modelo, Animação, Modelagem, Textura, Otimizar, Prévia, Ajuda**. Muita coisa também aparece no **clique direito** numa pasta, num cubo ou numa animação.

Regras gerais:
- **Tudo desfaz com um Ctrl+Z** (exceto onde está escrito "Desfazer no aviso").
- As ferramentas de animação pedem o **modo Animar** com uma animação selecionada.
- Os avisos do export **nunca bloqueiam**: você pode exportar mesmo assim.
- Teste sempre em uma **cópia** do seu modelo.

---

## 1. Levar pro jogo (direto no menu CPM+)

| Item | Como usar |
|---|---|
| **Levar pro jogo...** | Explica e oferece os caminhos: skin com o modelo dentro (funciona até em servidor sem o mod), arquivo .cpmmodel ou .cpmproject, e quem vai conseguir ver. Siga o passo a passo da janela. |
| **Exportar modelo (.cpmmodel)** | Gera o arquivo da lista "Modelos" do jogo. Ponha em `.minecraft/player_models` e use o comando que aparece (`/cpmclient set_model ...`). Também está em Arquivo > Exportar. |
| **Testar no jogo agora** | Faz uma cópia de teste e mostra o que fazer no jogo (T, Ctrl+V, Enter). |
| **Conferir uma skin...** | Abre uma skin .png e diz se ela tem modelo do CPM dentro, se está inteira e o tamanho. |
| **Quem pode ver meu modelo?** | Mostra se o modelo carrega pros outros jogadores em cada nível de segurança do CPM, e por quê (cubos, textura, tamanho do link). Só informa. |

## 2. Ajustes do modelo (CPM+ > Ajustes do modelo)

| Item | Como usar |
|---|---|
| **Ajustes do modelo…** | Braço **Steve (4 px) ou Alex (3 px)**. Marque "ajustar a largura dos braços" pra ele mexer nos cubos junto. Também: esconder a cabeça com crânio, tirar deslocamento da armadura/cama, brilho visível com invisibilidade. |
| **Nome, descrição e ícone…** | Nome e descrição que aparecem no jogo, ícone (redimensiona sozinho) e **quem pode copiar** o modelo (incluindo travar na sua conta). |
| **Tamanho e atributos…** | Escala do jogador, altura dos olhos, alcance etc. Valores diferentes de 1 só funcionam em servidor se o staff liberar; a janela mostra os comandos pro staff. |
| **ID do modelo** (botão dentro de Ajustes do modelo) | **Gerar** cria o ID; **Copiar** copia. Sem ID, o jogo não lembra as opções salvas ("Salvar entre partidas") nem os atalhos de quem usa o modelo. Use o mesmo ID em todas as versões. Trocar o ID tem "Desfazer no aviso". |
| **Camadas da skin pras animações (avançado)...** | Escolha quais camadas da skin o CPM usa pra mandar suas animações pros outros jogadores (quando o export reclamar de falta de espaço). |

## 3. Animação (CPM+ > Animação)

> **Aditiva ou não aditiva?** (Propriedades da animação > Aditiva) **Aditiva** = soma com o Minecraft: braços e pernas continuam balançando e a cabeça segue o mouse. **Não aditiva** = sua animação manda nas partes do corpo que ela mexe: o Minecraft para de balançá-las, e se você animar a cabeça ela para de seguir o mouse. Na dúvida, abra o **Raio-X** (seção "Conferir e arrumar"): ele mostra parte por parte o que acontece no jogo. O plugin também avisa na hora quando uma edição faz a cabeça parar de olhar pro mouse ou deixa um braço/perna balançando sem querer (clique no aviso pra abrir o Raio-X).

**🆕 Começar fácil**
| Item | Como usar |
|---|---|
| **Nova animação pelo que você quer...** (Ctrl+Alt+Shift+N) | Escolha o objetivo: algo mexendo o tempo todo (cauda, orelha, respiração), enfeitar o andar, trocar o andar inteiro, enfeitar o correr, emote/gesto (botão na roda de gestos), liga/desliga (asas abertas, chapéu), pose personalizada (sentar, deitar) ou peças que seguem pra onde a cabeça olha. A animação já nasce com o tipo do CPM, o loop e o "aditiva" certos, e a janela explica quando ela toca no jogo. Depois é só animar. |
| **Gerar andar / correr (2 ou 4 patas)...** | Cria o ciclo pronto em loop: pernas alternadas e braços opostos (ou trote de 4 patas, com as diagonais juntas), joelho/cotovelo dobrando (se o membro tiver dobra, veja "Membros dobráveis") e o corpo subindo e descendo. Escolha andando ou correndo, a duração do passo e quanto balança. Ele substitui o balanço do Minecraft nas pernas e braços; o passo tem sempre a mesma cadência (é o jeito de ter joelho/cotovelo no tempo certo). |
| **Kit de rosto (expressões, boca, olhos)...** | Monte o rosto em grupos (um grupo por expressão, um grupo de boca aberta, as pupilas) e escolha na janela. Cada expressão vira um botão liga/desliga que mostra aquele rosto e esconde os outros; a boca abre quando o jogador fala no chat de voz; os olhos deslizam conforme a cabeça gira. |
| **Testar no editor do CPM (aba Testar)** (também no clique direito da animação) | Abre o projeto no editor do CPM direto em Animação > Testar, com as animações do Minecraft ligadas e a sua animação escolhida: você vê como fica no jogo (balanço, olhar, pose) antes de exportar. |
| **Ajuda: vi isso num vídeo...** | Quase todo tutorial de CPM é gravado no editor do próprio CPM. A janela tem uma tabela "no vídeo" → "aqui": adicionar animação, tipo, aditiva, prioridade, quadros, interpolação, aba Testar, Copy Transform, esconder peça, nome do gesto (#), exportar, duplicar, loop e animação por valor. |

**Criar e gerar**
| Item | Como usar |
|---|---|
| **🆕 Física: seguir o movimento...** (Ctrl+Alt+U, também no clique direito da pasta ou da linha da timeline) | No modo Animar, na animação que as peças devem acompanhar (ex.: andar), selecione rabo, orelhas, cabelo, capa... Escolha o tipo de peça (rabo, orelha, cabelo, capa, antena, pena ou personalizado) e ajuste mole/duro, quique, peso, ângulo máximo, ponta mais solta e força. A prévia mostra na hora (desligue e ligue pra comparar). No OK ele cria a camada aditiva "nome (física)", que toca junto no jogo e fecha o loop sem emenda. Selecione a camada e abra de novo pra refazer com outros valores. |
| **Gerar movimento pronto...** | Escolha a peça e o movimento (respirar, flutuar, bater asas, balançar cauda, pêndulo, tremer, girar sem parar), se vai numa animação nova ou na atual, duração e ciclos. |
| **Gerar piscada...** | Escolha o grupo de olho aberto e o de olho fechado; intervalo regular ou aleatório. |
| **Criar seletor de cor...** | Selecione as peças: cria um controle deslizante na roda de gestos que troca a cor delas no jogo. |
| **Fazer onda…** | Selecione a primeira peça de uma corrente (rabo, cabelo, cachecol). **Onda nova**: eixo, força, quantidade e atraso. **Seguir a primeira peça**: anime só a primeira e as outras repetem com atraso. Também no clique direito da pasta (modo Animar). |
| **Partículas com sua imagem...** | Partículas falsas (peças animadas com a sua imagem). Escolha a imagem (pronta ou seu PNG), o estilo (explosão, órbita, chuva, brilhos, fumaça, atrás de você), de onde sai e quando aparece (sempre, andando, num gesto). O contador mostra o peso antes de criar. |
| **Soltar partículas daqui...** (clique direito numa pasta) | O mesmo, saindo da pasta escolhida (ex.: boca do dragão). |
| **Editar partículas...** | Selecione o grupo das partículas: muda as opções e refaz tudo. |
| **Animar bloco por bloco** (liga/desliga, fica lembrado) | No modo Animar, clique num bloquinho que divide o grupo com outros (ex.: um bloco da asa), **no modelo ou na lista de peças**: ele ganha **um osso só dele na hora**, com o pivô onde encosta no resto, e já fica selecionado pra animar. Não precisa separar grupo por grupo antes. Com a opção ligada, os bloquinhos aparecem na lista também no modo Animar. Nada sai do lugar, Ctrl+Z desfaz, e clicar de novo no mesmo bloco só seleciona o osso dele. Desligado, tudo volta ao normal do Blockbench (o clique pega o grupo inteiro). |
| **Lista só com grupos (no modo Animar)** (liga/desliga) | No modo Animar a lista de peças mostra só os grupos (ossos), sem os bloquinhos. O Blockbench 5 já faz isso sozinho; a opção serve pra quem ainda vê os blocos na lista. No modo Editar aparece tudo. |

**Mexer no movimento**
| Item | Como usar |
|---|---|
| **Pose intermediária (tween)...** (Ctrl+Alt+B, também no clique direito da pasta ou da linha da timeline) | Cria a pose no tempo atual entre a key anterior e a próxima: arraste de -100 (igual à anterior) a +100 (igual à próxima); passando de ±100 ela passa do ponto (até 150). Ou **Puxar pro repouso** (0% = pose de agora, 100% = pose normal). Vale pras peças selecionadas (com as de dentro, se quiser) ou, sem seleção, pra todas que se mexem. Num loop, antes da primeira key vem a última. Mostra no modelo enquanto você arrasta; só rotação, posição e tamanho (cor e visibilidade nunca). |
| **Animação rápida** (submenu) | Inverter, mudar velocidade, deixar tudo suave ou tudo reto. |
| **Exagerar ou suavizar movimento...** | 0% a 300% de quanto as peças se mexem. |
| **Marcar pose aqui...** | Cria um keyframe em todas as peças animadas no tempo atual, guardando a pose. |
| **Copiar pose das partes** (Ctrl+Alt+Shift+C, também no clique direito da pasta ou da linha da timeline) | Igual o “Copiar pose da parte” do CPM, mas **várias de uma vez**: guarda a pose de agora das partes selecionadas e de tudo dentro delas (nada selecionado = o modelo todo). |
| **Colar pose** (Ctrl+Alt+Shift+V) | Cola no tempo atual, em qualquer animação. Copiou UMA pasta (ex.: asa esquerda) e selecionou OUTRA (asa direita)? Cola nela e nas partes de dentro. |
| **Colar pose no outro lado (espelhada)** (Ctrl+Alt+Shift+B) | Cola nas partes do outro lado, já espelhada. Ex.: copie a asa esquerda e cole espelhado: a direita fica igual, do jeito certo. Acha o par mesmo se os cubos tiverem nomes diferentes (cube114 / cube58), pela posição na asa. |
| **Espelhar pose** (Ctrl+Alt+Shift+R) | Espelha a pose de agora: selecione as duas asas e elas trocam de lado; uma parte sozinha espelha no lugar (igual o “Espelhar pose da parte” do CPM). |
| **Fechar o loop (sem pulo no fim)** | Cada peça termina exatamente onde começa. |
| **Atrasar / adiantar movimento...** | Selecione peças (asa, rabo) e desloque o movimento no tempo; o loop continua fechado. |
| **Suavizar movimento (começa/termina devagar)...** | Selecione 2 ou mais keyframes do mesmo canal e escolha a curva. Se estiverem colados, aumente o fps. |
| **Girar/mover o corpo inteiro…** | Gira ou move o corpo todo numa pose; cabeça, braços e pernas acompanham. |
| **Criar cópia espelhada...** (também no clique direito da animação) | Cria uma animação nova com esquerda e direita trocadas. |
| **Transformar fórmulas em keyframes...** | Troca fórmulas Molang por keyframes, pra sair igual no jogo. |
| **🆕 Ajuste relativo (somar em todas as keys)...** (Ctrl+Alt+Y, também no clique direito da pasta ou da linha da timeline) | Selecione as peças (nada selecionado = todas as animadas) e digite a mudança: girar X/Y/Z, mover X/Y/Z, tamanho. A MESMA mudança entra em todas as keys, então a pose inteira desloca e o movimento continua igual (ex.: braço 10° mais aberto na animação toda). **Onde**: animação inteira, do playhead pra frente, do começo até o playhead ou entre dois tempos (com borda suave, sem degrau). **Em quais animações**: só nesta ou em todas que mexem nessas peças. Com "Ver na tela enquanto ajusto" você confere antes de aplicar. |
| **🆕 Camada de ajuste: criar / editar** (Ctrl+Alt+Shift+A, também no clique direito da animação) | Cria "nome (ajuste)", uma camada aditiva por cima da animação: corrija nela sem mexer nas keys originais (a original continua tocando junto, no Blockbench e no jogo). Apertar de novo volta pra original. **Camada de ajuste: força...** aumenta ou diminui tudo o que ela faz (0% a 150%). **Camada de ajuste: fundir na animação...** soma a camada nas keys da original e apaga a camada. |
| **🆕 Editar keys em várias animações...** (Ctrl+Alt+Z) | Marque as animações e escolha: mudar a velocidade (duração), mais forte / mais fraco, trocar a peça (as keys de uma peça vão pra outra), copiar a trilha de uma peça desta animação pras outras (pode esticar pra ocupar a animação inteira) ou apagar a trilha de uma peça. Um Ctrl+Z desfaz tudo. |
| **🆕 Inserir clipe (sequenciador)...** | Põe outra animação dentro desta, a partir do playhead: quantas vezes, velocidade, ao contrário, empurrar o resto pra frente (ou substituir o trecho) e bordas suaves na entrada e na saída. |
| **🆕 Curvas que não passam do ponto** | Com keys selecionadas (ou peças selecionadas: todas as keys delas): vira curva suave que nunca passa do valor das vizinhas, então pico e vale ficam retos (sem o "quique" do suave comum). |
| **🆕 Esticar / achatar (mantém o volume)...** (também no clique direito da linha da timeline) | Cria uma key de tamanho no playhead nas peças selecionadas: estica num eixo e afina os outros dois na medida certa, sem a peça ficar maior ou menor. Acima de 100% estica, abaixo achata. |
| **🆕 Girar em volta de outro ponto (pivô temporário)...** (também no clique direito da pasta) | Gira as peças selecionadas em volta do pivô de OUTRA peça, sem mudar pivô nenhum: cria as keys de rotação e posição certas no playhead. |
| **🆕 Takes (versões da animação)...** (também no clique direito da animação) | **Salvar take** guarda a animação como está; teste outra ideia à vontade e depois **Restaurar** o take ou **Misturar** com ele (quanto %). Ficam salvos no projeto e não vão pro jogo. |

**🆕 Entrada, saída e trocas de pose**
| Item | Como usar |
|---|---|
| **Entrada e saída suaves...** (também no clique direito da animação) | O CPM troca de pose seco, sem misturar. Numa animação em loop (andar, sentar, pose personalizada...), crie a **entrada** (toca 1x antes do loop: vai da pose de descanso, ou do começo de outra animação, até a primeira pose) e/ou a **saída** (toca 1x depois, de volta). **Terminar a volta antes de sair** liga o "deve terminar" do CPM, pra saída começar da pose certa. Rode **Fechar o loop** antes, se o fim do loop não encontra o começo. |
| **Relatório de estalos (trocas de pose)...** | Lista as peças que dão um pulo grande quando o jogo troca de pose (parado → andando, andando → correndo, agachar...), com o pior caso e a média. **Criar entrada suave** resolve direto. Também aparece como aviso no export. |

**🆕 Ver melhor** (também em CPM+ > Prévia; só mudam a tela, nunca a animação nem o jogo)
| Item | Como usar |
|---|---|
| **Rastro das pontas** (liga/desliga) | Desenha o caminho que a PONTA de cada peça selecionada faz na animação (o rastro do Blockbench só segue o pivô). Cor = velocidade (azul devagar, vermelho rápido); cada pontinho é um quadro que vai pro jogo; clique num pontinho pra ir pra ele. |
| **Prévia em degrau (blocking)** (liga/desliga) | A tela segura cada pose até a próxima key, pra conferir só as poses-chave, sem o meio-termo. |
| **Silhueta** (liga/desliga) | Pinta o modelo inteiro de escuro pra ler a pose pelo contorno. |
| **Clicar de novo pega a peça de trás** (liga/desliga, vem ligado) | Clicou e pegou a peça da frente? Clique de novo no mesmo lugar: seleciona a que está atrás, e assim por diante. |

**Mostrar, esconder e reagir**
| Item | Como usar |
|---|---|
| **Mostrar/esconder nesta animação…** | Selecione peças: elas aparecem ou somem só nessa animação (ou num trecho). |
| **Reações automáticas…** | "Quando [capacete, elytra, dano...] então esconder/mostrar estas peças". |
| **Criar forma com movimento próprio…** | A peça anda/corre diferente quando um botão (camada) está ligado. |
| **Juntar no mesmo gesto... / Separar deste gesto...** | Gesto em várias partes (regra "Nome#parte" do CPM): várias animações tocam como um gesto só. |

**Conferir e arrumar**
| Item | Como usar |
|---|---|
| **🆕 Conferir movimento...** (Ctrl+Alt+Shift+K, também no clique direito da animação) | Procura o que costuma ficar feio no jogo: pico de 1 quadro, degrau esquecido no meio de keys suaves, pulo ou tranco na emenda do loop, gesto que começa/termina fora do repouso, movimento robótico (só keys Linear), dois lados fazendo exatamente o mesmo e perna/braço que sai do ritmo do passo do Minecraft. Vermelho = vai aparecer no jogo, amarelo = provável, azul = dica de estilo. **Ir pra lá** leva até a peça e o tempo. |
| **🆕 Raio-X: quem mexe em cada parte?** | Pra animação selecionada, mostra em cada parte do corpo: soma com o Minecraft, trava o balanço (e se trava rotação, posição ou os dois), desliga o olhar (cabeça para de seguir o mouse) ou tem escala que o jogo ignora. Botões por parte: **Travar aqui (parar o balanço)**, **Devolver pro Minecraft** (apaga as keys ou leva pra um sub-grupo) e **Criar sub-grupo de animação…**. Embaixo, o resumo de todas as animações. |
| **🆕 Criar sub-grupo de animação…** (clique direito numa parte do corpo: cabeça, corpo, braço, perna) | Cria "parte_anim" dentro da parte do corpo, com o mesmo pivô, e leva pra ele as peças e as keys escolhidas. Resolve 3 coisas: a escala passa a funcionar no jogo, o movimento soma com o do Minecraft, e o braço/perna para de "flutuar" quando a animação toca agachado ou nadando (regra do CPM: numa animação não aditiva, girar o membro também trava a posição dele na de pé). |
| **Movimento que some ao trocar de situação…** | Lista peças que se mexem andando/parado mas param ao pular, cair ou agachar. Opções: **Copiar pras situações que faltam**, **Mover pra Global** ou **É de propósito**. |
| **Animações soltas...** | Keyframes de pastas que você apagou ficam guardados. Refez a peça? Reconecte (ele sugere a pasta de mesmo nome), junte ou apague. |
| **Otimizar animação (menos quadros)…** | Deixa o arquivo menor: escolha a tolerância (graus/pixels/escala), veja a tabela antes/depois e aplique. Visibilidade e cor nunca mudam. Também em CPM+ > Otimizar. |
| **Limpar cores fantasmas...** | Apaga keys de cor que não fazem nada no jogo. |
| **Cola de comandos...** | Comandos `/cpmclient animate`, `/cpm animate` e `/cpm detect` prontos, com aspas quando precisa. "Copiar tudo". |

**Importar**
| Item | Como usar |
|---|---|
| **Importar animações de outro .cpmproject…** | Escolha o arquivo; ele mostra quantas peças animadas existem no seu modelo (casa pelo ID do CPM ou pelo caminho de pastas). Marque as animações e importe. |
| **Importar animação Bedrock/GeckoLib...** | Escolha o `.animation.json` e diga qual peça do seu modelo cada osso mexe. Também em Animação na barra do Blockbench. |
| **🆕 Importar emote (Emotecraft / PlayerAnimator)...** | Escolha o `.json` do emote: ele vira um gesto do CPM (botão na roda de gestos) com as suavizações do emote, loop com entrada, o movimento do jogador inteiro distribuído nas partes do corpo e as dobras de cotovelo/joelho (se o modelo tiver "Membros dobráveis"; se não tiver, ele avisa). |

**Propriedades da animação** (clique duplo na animação): ajustes avançados (precisa terminar, esconder da roda, slider) e **Qualidade (fps)** com a contagem exata de quadros.

## 4. Modelagem (CPM+ > Modelagem, ou clique direito)

| Item | Como usar |
|---|---|
| **Espelhar pro outro lado (CPM)…** | Selecione a peça de um lado (ex.: asa no braço esquerdo): cria a cópia espelhada do outro lado, com nomes, "seguir peça" e slot de item trocados. Pode espelhar as animações junto ou só mover. |
| **Grudar na parte do corpo...** | A peça passa a acompanhar outra parte do corpo no jogo (cabeça, braço...) sem sair do lugar, mesmo com pastas giradas. |
| **Copiar movimento de...** | Editor de Copy Transform: a pasta copia posição, rotação, tamanho, cor ou visibilidade de outra peça. Avisa se a escolha não vai funcionar no jogo. |
| **Fazer camada de armadura...** (clique direito no cubo) | Os cubos passam a mostrar a armadura que o jogador está usando. |
| **Sem animação do Minecraft** | Com uma parte do corpo selecionada: ela para de seguir o balanço do jogo (andar, correr, bater). |
| **Escolher slot de item...** | A pasta vira o lugar onde um item fica (mão, cabeça, ombro). "Nenhum" volta ao normal. |
| **Criar cópia da parte do corpo...** | Cria `body_dup` (ou `head_dup`...) sem animação do Minecraft. Peças soltas selecionadas vão pra cópia sem sair do lugar. |
| **Conferir articulações...** | Avisa quando o pivô da pasta está longe da junta, com conserto de 1 clique. |
| **Montar corrente... / Fazer osso desta peça** | Cauda, pescoço e trança viram ossos com pivô na emenda. |
| **Membros dobráveis (cotovelo, joelho, cintura)...** | Divide braço, perna ou corpo em dois pra parte de baixo dobrar, sem estragar a skin. No clique direito: Criar cotovelo/joelho, Testar dobra, Desfazer. |
| **Curvar peça (saia, poncho, cabelo, cauda)...** | O cubo vira uma corrente de fatias em arco. |

## 5. 🆕 Pintar: painel CPM+ Pintar

Na aba **Pintar**, embaixo do seletor de cores, fica o painel **CPM+ Pintar** (uma aba ao lado do Organizador). Tudo de arte do CPM+ está nele, com o nome escrito em cada botão:
- **Ferramentas:** Sombrear, Esfumar, Esfumar pixel, Pontilhar, Variação e Degradê pixel. Clique e pinte. As opções aparecem embaixo do painel; as menos usadas ficam fechadas em **Mais opções**. O **Esfumar** e o **Esfumar pixel** também estão na fileira de ferramentas do Blockbench, **logo depois da Borracha**.
- **Pincéis:** 45 pincéis em 6 grupos. Clique num grupo pra ver a prévia de cada um, e clique no pincel.
- **Cores e automático:** Criar tons, Trocar cor, Sombra no modelo e Ruído na textura. Abrem uma janela com prévia ao vivo no modelo; o resto também fica em **Mais opções**.

Também dentro do próprio Blockbench: **clique direito numa textura** (Sombra no modelo, Ruído na textura, Trocar cor), a **setinha ao lado do Pincel** do Blockbench (lista dos pincéis do CPM+), os botões **Criar tons** e **Trocar cor** embaixo do seletor de cores, e **Ruído** e **Sombra no modelo** no painel de Texturas. E tudo em **CPM+ > Textura**. Se o painel sumiu: **CPM+ > Textura > Mostrar o painel CPM+ Pintar**. Na barra de cima só entram o Esfumar e o Esfumar pixel (ao lado da Borracha); o resto fica com as coisas do Blockbench.

Regras: cada traço é **um Ctrl+Z**; nada vaza pra outra face do modelo; nunca pinta onde a textura é transparente (exceto os pincéis que pintam por cima de tudo). O **Tamanho** é o mesmo do pincel do Blockbench (também aparece no painel).

| Ferramenta | Como usar |
|---|---|
| **Sombrear** | Passe por cima do que já está pintado. Escolha **Escurecer** ou **Clarear** no painel. A sombra puxa pro azul e a luz pro amarelo, sem precisar escolher cor. *Mais opções:* degrau da rampa (só tons que já existem na textura), matiz/sat/luz, uma vez por traço. |
| **Esfumar** | Arraste em cima da **divisa entre duas cores**: aparecem os tons do meio, usando as cores que já estão ali (não precisa escolher cor). Passar de novo com o pincel maior alarga a mistura. *Mais opções:* arrastar a cor (como um dedo), arrastar pixels, embaralhar pixels, só cores que já existem. |
| **Esfumar pixel** | Igual ao Esfumar, mas pra pixel art: a divisa vira um **xadrez das duas cores**, sem criar cor nova. Passar de novo alarga. |
| **Pontilhar** | Pinta pontinhos alinhados aos pixels com a cor 1. **Densidade** = quantos pixels recebem a cor. *Mais opções:* padrão (xadrez, Bayer, linhas...), buracos com a cor 2, deslocar o padrão. |
| **Variação** | Passe numa área lisa: cada pixel muda um pouquinho (tira o "chapado"). **Cobertura** = quantos pixels mudam. *Mais opções:* tipo e intensidade. |
| **Degradê pixel** | Arraste de onde o degradê começa até onde termina (cor 1 até cor 2), em degraus de pixel art. **Degraus** e **Pontilhado** no painel. *Mais opções:* cor 1 até transparente, rampa salva, radial. O botão Degradê do próprio Blockbench continua fazendo o degradê normal. |
| **Criar tons** | Gera os tons de sombra e luz de uma cor (ou entre duas cores). Clique num tom pra usar como cor 1. **Pôr na paleta** ou **Salvar em Minhas rampas** (o Sombrear e o Degradê pixel usam). |
| **Trocar cor** | Clique numa família de cores (ex.: todos os vermelhos da roupa) e mude o **Matiz**: todos os tons mudam juntos e o sombreado fica igual. **Virar a cor 1** leva a família pra cor selecionada. |
| **Sombra no modelo** | Luz e sombra calculadas pela forma do modelo: escolha **de onde vem a luz** e confirme. Funciona nas peças selecionadas ou no modelo todo; depois é só retocar à mão. |
| **Ruído na textura** | A Variação de uma vez: na textura inteira, na seleção ou nas peças selecionadas. |

### 🆕 Pincéis (45)

Pincéis no estilo dos mais usados pela comunidade dos apps de desenho (os mais instalados da galeria do ibisPaint), refeitos do zero. Ficam na parte **Pincéis** do painel CPM+ Pintar. Cada pincel tem dois jeitos:
- **HD**: pontas desenhadas, bordas lisas, textura e mistura de cor, como num app de desenho (lápis com grão, render que mistura com a cor de baixo, óleo com cerdas, fita de fogo com brilho, nuvens, penas...). Pra texturas de 128 px ou mais.
- **Pixel art**: carimbos desenhados pixel a pixel, sem cor nova. Pra skin 64×64.

O CPM+ escolhe sozinho pelo tamanho da textura, e a prévia de cada pincel já mostra o jeito que vai sair. Pra trocar na mão: **Mais opções > Estilo** (Automático, HD, Pixel art).

- **Cor 1 e cor 2:** o clique esquerdo pinta com a cor 1, e a cor 2 entra nos detalhes (miolo da flor, contorno cor 2, xadrez, pontos da cicatriz). O clique direito troca as duas.
- O **Tamanho** muda o tamanho dos carimbos, fios e padrões. Nos carimbos, *Mais opções* tem **Espalhar** e **Variação** (0 a 200%).
- Passar de novo no mesmo lugar, no mesmo traço, **não acumula**. Os padrões (xadrez, listras, escamas, jeans, tricô...) ficam **presos à textura**: pinte em vários traços que o padrão continua alinhado.

| Grupo | Pincéis |
|---|---|
| **Desenho e pintura** | **Lápis com grão** (grafite que afina nas pontas, grão na borda), **Lineart** (afina no começo e no fim; em 1px faz linha pixel-perfect), **Contorno auto** (cor + contorno escuro), **Contorno cor 2**, **Renderizar** (cobre com borda macia e mistura um pouco com a cor de baixo), **Mesclar suave** (aerógrafo leve: passe na divisa de duas cores pra mesclar; em 64×64 é pontilhado, sem cor nova), **Aquarela**, **Óleo / guache** (cerdas, arrasta a cor de baixo), **Arco-íris**, **Pixel 1px** (atalho: o Pincel do Blockbench em 1px com pixel-perfect) |
| **Cabelo, pelo e escamas** | **Cabelo (fios)**, **Cachos**, **Brilho de cabelo** (só clareia o que já está pintado), **Pelo**, **Penas**, **Escamas** |
| **Natureza e efeitos** | **Grama**, **Folhagem** (tufos de borda serrilhada em cor chapada, no estilo do pincel de grama mais instalado da comunidade: grama, arbusto, copa de árvore), **Folhas**, **Flores**, **Nuvens**, **Fogo**, **Respingos** (sangue, lama, tinta), **Bolhas** |
| **Brilho, glitter e rosto** | **Glitter**, **Brilhos**, **Estrelas**, **Neon**, **Blush**, **Olhos (íris)** |
| **Enfeites** | **Corações**, **Renda**, **Pérolas**, **Corrente**, **Costura**, **Cicatriz** |
| **Padrões e texturas** | **Xadrez**, **Listras**, **Bolinhas**, **Xadrez escocês**, **Jeans**, **Tricô**, **Papel**, **Grão**, **Retícula** |

Dicas: **Fogo** fica melhor com laranja como cor 1 (em HD é uma fita de fogo com miolo claro e brilho, que fica ainda melhor em fundo escuro). **Nuvens**: pinte com 2 ou 3 tons pra dar volume. **Folhagem**: é de uma cor só, como o original; pinte primeiro o verde escuro e depois um verde mais claro por cima, mais pro alto, pra dar volume. Dá pra clicar (um tufo) ou arrastar (uma faixa). **Brilho de cabelo**, **Papel** e **Grão** vão por cima de uma cor já pintada. **Olhos**: um clique em cada olho. **Renda**, **Pérolas** e **Corrente** ficam mais bonitas em traço reto (segure Shift pra fazer linha reta).

## 6. Textura e otimização

| Item | Onde | Como usar |
|---|---|---|
| **Pintar: pele de baixo da 2ª camada** (e Mostrar tudo / Só a 2ª camada / Só o selecionado / Alternar) | CPM+ > Prévia | O pincel atravessa a camada transparente pra pintar a pele de baixo. |
| **Conferir simetria (pintura espelhada)** | CPM+ > Textura | Mostra peças em que o pincel espelhado não pinta e ajuda a alinhar. |
| **Achar peças de cor única** / **Virar cor sólida…** | CPM+ > Otimizar / clique direito no cubo | Peça de uma cor só vira cubo de cor (menos textura). |

## 7. Prévia (CPM+ > Prévia)

| Item | Como usar |
|---|---|
| **Girar um eixo por vez** | Ligado: girar no X não mexe Y e Z no gizmo. |
| **Ver esqueleto** | Desenha ossos, juntas e raízes do CPM por cima do modelo. |
| **Item na mão (prévia do editor do CPM)** / **Item na mão...** | Mostra um item na mão pra conferir poses; escolha o item. Só visual, não vai no arquivo. |
| **🆕 Rastro das pontas, Prévia em degrau, Silhueta, Clicar de novo pega a peça de trás** | Veja a seção 3, "Ver melhor". |

## 8. Ajuda (CPM+ > Ajuda)

- **Limpar armazenamento do CPM...**: mostra quanto espaço o editor do CPM usa dentro do Blockbench e apaga autosaves antigos.

---

## 9. Avisos automáticos no export (Arquivo > Exportar .cpmproject)

O plugin confere o modelo antes de exportar e mostra uma lista. Cada linha tem **Mostrar** (acha a peça) e, quando dá, um botão de conserto.

| Aviso | O que significa | Conserto |
|---|---|---|
| **Limites do jogo** | Cubo maior que 25,5 px ou posição mais longe que 48 px (inclusive em animação). O jogo corta sem avisar; a linha mostra o valor que ele vai usar. | Divida o cubo / aproxime o pivô. |
| **Animação grande demais** | Mais de 255 quadros, 32,7 s ou 255 peças: toca só o começo, quebra ou mexe a peça errada. | **Ajustar** (baixa o fps ou abre o Otimizar). |
| **Parte do corpo fantasma** | Apagou/desligou a pasta de uma parte do corpo e o jogo mostra a parte normal do Minecraft. | **Esconder a parte normal**, **Quero a parte normal** ou **Ligar a exportação**. |
| **Peças soltas** | Cubos fora de qualquer pasta vão pro Corpo no jogo. | **Resolver…**: Grudar onde estão / Mover para o Corpo. |
| **Animações soltas** | Keyframes de peças apagadas não vão pro jogo. | **Reconectar…** |
| **Raiz desconhecida** | Pasta no topo com nome que o CPM não conhece. | Escolher a parte do corpo (já vem sugerida pela posição). |
| **Falta o ID do modelo** | Opções "Salvar entre partidas" não serão lembradas. | **Criar ID**. |
| **Botão renomeado** | Quem usa o modelo perde o atalho/opção salva. | **Voltar ao nome antigo** ou **Está certo**. |
| **Movimento que some** | Peça que para ao pular/cair/agachar (só informa). | Abre a ferramenta. |
| **🆕 Troca de pose com pulo** | Peça que dá um pulo grande quando o jogo troca de pose (parado → andando...). Só informa. | Abre o Relatório de estalos. |
| **🆕 Cabeça para de seguir o mouse** | Animação não aditiva com keys na cabeça desliga o olhar. Só informa (às vezes é de propósito). | Abre o Raio-X (Devolver pro Minecraft). |
| **Fórmulas e camadas da skin** | Animação com fórmula Molang ou sem espaço nas camadas da skin. | Transformar em keyframes / escolher camadas. |

## 10. Correções automáticas (não precisa fazer nada)

- Animações saem iguais no jogo (sem quadros extras, suave continua suave, toggle de 1 quadro, fórmulas).
- Modelos pesados abrem, salvam e exportam bem mais rápido, com barra de progresso.
- Grupos não ficam escondidos depois do modo Animar.
- "Copiar cor" do Copy Transform não se perde.
- Ctrl+Z de "Adicionar slots/partes" e dos consertos desfaz tudo de uma vez.
- Apagar uma pasta animada guarda as keys (veja Animações soltas).

---
Código-fonte (MIT): https://github.com/solismissus-bot/CPM-Plugin-Plus · Novidades de cada versão: COMO_TESTAR.md
